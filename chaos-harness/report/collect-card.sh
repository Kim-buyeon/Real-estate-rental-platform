#!/usr/bin/env bash
# 성능 카드 회차 전후 DB · Redis 통계 — 시험 계획서 7.3 · 이슈 #290. 운영자 PC 에서 SSH 로 돈다(노드에 아무것도 설치하지 않는다).
#
#   SSH_CONFIG=<ssh 설정> bash collect-card.sh start <LABEL> [OUTDIR]     회차 직전 — 질의 통계 초기화 · 테이블 통계 · Redis 스냅샷
#   SSH_CONFIG=<ssh 설정> bash collect-card.sh end   <LABEL> [OUTDIR]     회차 직후 — 같은 스냅샷 + 상위 질의 + 테이블 통계 차이
#   SSH_CONFIG=<ssh 설정> bash collect-card.sh explain <LABEL> <sql 파일> [OUTDIR]   EXPLAIN (ANALYZE, BUFFERS) — 읽기 전용 트랜잭션
#
# 카드 항목 — ③ 요청당 질의 수(상위 질의 calls 합 ÷ k6 요청 수) · ⑤ 상위 질의 · ⑥ 전체 스캔(tables-diff 의 seq_scan) · 버퍼 적중.
# pg_stat_statements 뷰는 primary 의 postgres DB 에 있다(운영 절차서 2장 「질의 통계」) — 초기화 · 조회는 거기서, 결과는 앱 DB(dbname)로 거른다.
# 상위 질의는 앱 DB 사용자의 것만 본다 — postgres exporter(모니터 역할 POSTGRES_MONITOR_USER)가 같은 앱 DB 에 붙어 상위 칸을 차지해 앱
# 질의가 잘렸다(9/28). 앱은 POSTGRES_USER 로 붙고(application.yml datasource.username) 이 스크립트의 psql 도 컨테이너의 같은
# $POSTGRES_USER 로 붙으므로 current_user 가 곧 앱 사용자다 — 이름을 명령줄에 옮기지 않는다. 이 스크립트가 앱 DB 에서 뜨는 테이블 ·
# 입출력 통계 질의도 같은 사용자라 pg_stat(io)_user_tables 를 읽는 질의는 뺀다. 칸은 100 개(앱 질의 종류가 20 을 넘는다).
# 테이블 통계(pg_stat_user_tables)는 앱 DB 에서 본다 — 초기화하지 않고 전후 차이로 잰다(pg_stat_reset 은 다른 관측도 지운다).
# Redis 는 APP-01 의 redis 컨테이너 — 비밀번호는 컨테이너 환경 변수(REDIS_PASSWORD)를 REDISCLI_AUTH 로 넘겨 출력 · 명령줄에 남기지 않는다.
set -euo pipefail

CMD=${1:?start · end · explain}
LABEL=${2:?LABEL}
SSH_CONFIG=${SSH_CONFIG:?SSH_CONFIG — app01 · db01 호스트가 있는 ssh 설정}
PG=${PG_CONTAINER:-rental-prod-postgres-1}
RD=${REDIS_CONTAINER:-rental-prod-redis-1}
if [ "$CMD" = explain ]; then SQLFILE=${3:?sql 파일}; OUTDIR=${4:-results-card}; else OUTDIR=${3:-results-card}; fi
D="$OUTDIR/$LABEL"
mkdir -p "$D"

ssh_() { ssh -F "$SSH_CONFIG" -o BatchMode=yes "$@"; }
# psql — stdin 으로 SQL 을 넘긴다(따옴표 중첩을 피한다). -A -F, 로 CSV 모양, 결과 줄만(-t 는 쓰지 않는다 — 머리글을 남긴다)
psql_() { local db=$1; ssh_ db01 "sudo docker exec -i $PG sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $db -A -F,'"; }
appdb() { ssh_ db01 "sudo docker exec $PG sh -c 'echo \$POSTGRES_DB'"; }
redis_() { ssh_ app01 "sudo docker exec $RD sh -c 'REDISCLI_AUTH=\"\$REDIS_PASSWORD\" redis-cli $*'"; }

TABLES_SQL="SELECT relname, seq_scan, seq_tup_read, COALESCE(idx_scan,0) AS idx_scan, n_tup_ins, n_tup_upd, n_tup_del,
  n_live_tup FROM pg_stat_user_tables ORDER BY relname;"
IO_SQL="SELECT relname, heap_blks_read, heap_blks_hit, COALESCE(idx_blks_read,0) AS idx_blks_read, COALESCE(idx_blks_hit,0) AS idx_blks_hit
  FROM pg_statio_user_tables ORDER BY relname;"

snapshot() {
  local tag=$1 db
  db=$(appdb | tr -d '\r')
  echo "$TABLES_SQL" | psql_ "$db" > "$D/tables-$tag.csv"
  echo "$IO_SQL" | psql_ "$db" > "$D/io-$tag.csv"
  redis_ INFO stats > "$D/redis-stats-$tag.txt"
  redis_ INFO memory > "$D/redis-memory-$tag.txt"
  date +%s > "$D/epoch-$tag.txt"
}

case "$CMD" in
  start)
    echo "SELECT pg_stat_statements_reset();" | psql_ postgres > /dev/null
    redis_ SLOWLOG RESET > /dev/null
    snapshot start
    echo "start $LABEL $(date '+%F %T')"
    ;;
  end)
    snapshot end
    db=$(appdb | tr -d '\r')
    echo "SELECT s.calls, round(s.total_exec_time::numeric,1) AS total_ms, round(s.mean_exec_time::numeric,3) AS mean_ms, s.rows,
      s.shared_blks_hit, s.shared_blks_read, replace(left(regexp_replace(s.query, '\\s+', ' ', 'g'), 200), ',', ';') AS query
      FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid JOIN pg_roles r ON r.oid = s.userid
      WHERE d.datname = '$db' AND r.rolname = current_user AND s.query !~ 'pg_stat(io)?_user_tables'
      ORDER BY s.total_exec_time DESC LIMIT 100;" | psql_ postgres > "$D/top-queries.csv"
    redis_ SLOWLOG GET 20 > "$D/redis-slowlog.txt"
    # 테이블 통계 차이 — 같은 relname 끼리 end - start. 한 번도 안 바뀐 테이블은 뺀다
    PY=""
    for c in python3 python; do "$c" -c 1 >/dev/null 2>&1 && { PY=$c; break; }; done   # Windows 의 python3 는 빈 껍데기일 수 있다
    [ -n "$PY" ] || { echo "python 이 없다 — tables-diff.csv 를 만들지 못했다" >&2; exit 1; }
    "$PY" - "$D" <<'PY'
import csv, sys, os
d = sys.argv[1]
def load(p):
    with open(p, encoding='utf-8') as f:
        rows = [r for r in csv.reader(f) if r and not r[0].startswith('(')]
    head, body = rows[0], rows[1:]
    return head, {r[0]: r for r in body}
h, a = load(os.path.join(d, 'tables-start.csv'))
_, b = load(os.path.join(d, 'tables-end.csv'))
with open(os.path.join(d, 'tables-diff.csv'), 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f)
    w.writerow(h[:7])
    for k in sorted(b):
        if k not in a:
            continue
        diff = [int(b[k][i]) - int(a[k][i]) for i in range(1, 7)]
        if any(diff):
            w.writerow([k] + diff)
PY
    echo "end $LABEL $(date '+%F %T') — $D"
    ;;
  explain)
    db=$(appdb | tr -d '\r')
    { echo "BEGIN READ ONLY;"; echo "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)"; cat "$SQLFILE"; echo ";"; echo "ROLLBACK;"; } \
      | ssh_ db01 "sudo docker exec -i $PG sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $db'" > "$D/explain.txt"
    echo "explain $LABEL — $D/explain.txt"
    ;;
  *) echo "start · end · explain" >&2; exit 2 ;;
esac
