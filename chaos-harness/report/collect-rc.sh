#!/usr/bin/env bash
# 성능 원인 직접 증거 수집 — 이슈 #292. collect-card.sh 옆의 도구로, 운영자 PC 에서 SSH 로 돈다(노드에 설치하는 것은 없다 —
# 샘플러는 노드 /tmp 의 셸 · 기존 컨테이너의 psql 로 돌고 end 가 지운다).
#
#   SSH_CONFIG=<ssh 설정> bash collect-rc.sh start       <LABEL> [OUTDIR]              회차 직전
#   SSH_CONFIG=<ssh 설정> bash collect-rc.sh end         <LABEL> [OUTDIR]              회차 직후
#   SSH_CONFIG=<ssh 설정> bash collect-rc.sh explain-cmp <LABEL> <sql 파일> [OUTDIR]   계획 비교(읽기 전용)
#
# 호스트 — SSH_CONFIG 의 app01 · app02 · db01. OUTDIR 기본 results-rc, 결과는 OUTDIR/LABEL/. LABEL 은 영문 · 숫자 · . _ - 만.
# RC_MAX_SEC(기본 7200) — 샘플러가 end 없이도 스스로 끝나는 상한(초). end 를 잊어도 노드에 계속 남지 않게 한다.
#
# start — ① pg_stat_statements 초기화(postgres DB) ② 테이블 · 인덱스 통계 스냅샷(앱 DB, 초기화하지 않고 차이로 잰다)
#         ③ 시작 시각(db01 시계, UTC — docker logs --since 에 그대로 쓴다) ④ 샘플러 셋을 노드 안에서 nohup · setsid 로 띄운다 —
#         SSH 가 끊겨도 돈다.
#   db01  — 앱 역할의 pg_stat_activity 를 1초마다(ts, state, wait_event_type, wait_event, 질의 앞 80자) /tmp/rc-<LABEL>-act.csv 에.
#           psql 한 연결이 \watch 로 되풀이한다 — 연결을 매초 새로 맺지 않는다. 앱 연결이 없는 틱에도 빈 행 하나를 남겨(LEFT JOIN)
#           틱 수가 곧 표본 초가 된다. 샘플러 자신의 연결은 pid 로 뺀다.
#   app01 · app02 — 5초마다 MemAvailable 과 docker stats --no-stream(컨테이너별 CPU · 메모리)을 /tmp/rc-<LABEL>-mem.txt 에.
# end   — 샘플러를 멈추고(pid 파일) 회수한 뒤 노드 /tmp 파일을 지운다. 그리고
#   statements.csv          앱 역할 · 앱 DB 질의 **전체**(LIMIT 없음), COPY … TO STDOUT WITH CSV — 질의 전문의 쉼표 · 줄바꿈도 안전하다.
#                           class 열 — txn(BEGIN · COMMIT · ROLLBACK · SET · SHOW 등) · self(pg_stat 계열 — 이 스크립트 · 샘플러가
#                           같은 역할로 붙는다) · app(나머지)
#   statements-summary.txt  class 별 합계 · app 질의 실행 시간 비중
#   tables-*.csv · indexes-*.csv · tables-diff.csv · indexes-diff.csv    end − start(바뀐 것만)
#   activity.csv · activity-summary.txt   대기 이벤트 비율(active 행 기준, 대기 없음 = CPU 로 센다)
#   mem-app01.txt · mem-app02.txt
#   auto-explain.txt · auto-explain-summary.txt   postgres 컨테이너 docker logs --since <start> 에서 auto_explain 블록
#                           (「duration: … plan:」 줄에서 다음 로그 줄 전까지). 원시 로그는 저장하지 않는다 — 「Query Parameters:」 줄은
#                           지운다(가입 · 로그인 질의의 파라미터가 개인정보다). 요약 — 블록 수 · 계획 안에 $n 이 있는 블록 수(= 일반 계획.
#                           질의 문장 쪽의 $n 은 세지 않는다) · 조인 방식별 수 · 질의별 수.
# explain-cmp — sql 파일은 PREPARE q(…) AS …; 와 「-- EXEC: EXECUTE q(값들);」 한 줄을 담는다(예: sql/map-clusters.sql). 세 방식을 각각
#         다른 연결 · 읽기 전용 트랜잭션에서 EXPLAIN (ANALYZE, BUFFERS) EXECUTE 한다. 방식마다 한 번 먼저 실행해(출력 버림) 캐시를 데운 뒤 잰다.
#   explain-custom.txt   SET LOCAL plan_cache_mode = force_custom_plan
#   explain-generic.txt  SET LOCAL plan_cache_mode = force_generic_plan
#   explain-planner.txt  custom + SET LOCAL effective_cache_size = '512MB' · random_page_cost = 1.1
#
# 비밀 — psql 은 컨테이너의 $POSTGRES_USER · $POSTGRES_DB 로 붙는다(컨테이너 안 sh 가 펼친다). 앱도 같은 역할이라 current_user 가 곧
# 앱 역할이다(collect-card.sh 머리 주석). 이름 · 비밀번호를 명령줄 · 출력에 옮기지 않는다.
set -euo pipefail

CMD=${1:?start · end · explain-cmp}
LABEL=${2:?LABEL}
SSH_CONFIG=${SSH_CONFIG:?SSH_CONFIG — app01 · app02 · db01 호스트가 있는 ssh 설정}
PG=${PG_CONTAINER:-rental-prod-postgres-1}
MAX_SEC=${RC_MAX_SEC:-7200}
APPS="app01 app02"
case "$LABEL" in *[!A-Za-z0-9._-]*) echo "LABEL 은 영문 · 숫자 · . _ - 만 — 노드 /tmp 파일 이름에 들어간다" >&2; exit 2 ;; esac
if [ "$CMD" = explain-cmp ]; then SQLFILE=${3:?sql 파일}; OUTDIR=${4:-results-rc}; else OUTDIR=${3:-results-rc}; fi
D="$OUTDIR/$LABEL"
mkdir -p "$D"

# 부하 중 APP-01 을 거치는 SSH 가 오류 없이 매달린 적이 있다(9/29 — 23분) — 호출마다 상한을 둔다
ssh_() { timeout "${RC_SSH_TIMEOUT:-300}" ssh -F "$SSH_CONFIG" -o BatchMode=yes "$@"; }
# psql — stdin 으로 SQL 을 넘긴다. 앱 DB 는 컨테이너의 $POSTGRES_DB, postgres DB 는 질의 통계 뷰가 있는 곳(운영 절차서 2장 「질의 통계」).
# appdb 변수는 COPY 질의가 앱 DB 로 거를 때 :'appdb' 로 쓴다
psql_() { local db=$1; shift
  ssh_ db01 "sudo docker exec -i $PG sh -c 'psql -X -q -v ON_ERROR_STOP=1 -v appdb=\"\$POSTGRES_DB\" -U \"\$POSTGRES_USER\" -d $db $*'"; }
psql_app() { psql_ '"$POSTGRES_DB"' "$@"; }

PY=""
for c in python3 python; do "$c" -c 1 >/dev/null 2>&1 && { PY=$c; break; }; done   # Windows 의 python3 는 빈 껍데기일 수 있다
[ -n "$PY" ] || { echo "python 이 없다 — 차이 · 요약을 만들지 못한다" >&2; exit 1; }

TABLES_SQL="SELECT relname, seq_scan, seq_tup_read, COALESCE(idx_scan,0) AS idx_scan, COALESCE(idx_tup_fetch,0) AS idx_tup_fetch,
  n_tup_ins, n_tup_upd, n_tup_del, n_live_tup FROM pg_stat_user_tables ORDER BY relname;"
INDEXES_SQL="SELECT relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch FROM pg_stat_user_indexes ORDER BY relname, indexrelname;"

snapshot() {
  local tag=$1
  echo "$TABLES_SQL" | psql_app --csv > "$D/tables-$tag.csv"
  echo "$INDEXES_SQL" | psql_app --csv > "$D/indexes-$tag.csv"
}

start_db01() {
  ssh_ db01 bash -s -- "$LABEL" "$PG" "$MAX_SEC" <<'REMOTE'
set -eu
L=$1 PG=$2 MAX=$3
[ ! -e "/tmp/rc-$L-act.csv" ] || { echo "db01 /tmp/rc-$L-act.csv 가 이미 있다 — 같은 LABEL 이 돌고 있거나 end 를 하지 않았다" >&2; exit 1; }
# 앱 연결이 없는 틱에도 빈 행 하나 — 틱 수 = 표본 초. 시각은 서울 벽시계(세션 timezone 에 기대지 않는다)
cat > "/tmp/rc-$L-act.sql" <<SQL
SELECT to_char(clock_timestamp() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS ts, a.state, a.wait_event_type, a.wait_event,
       left(regexp_replace(a.query, '\s+', ' ', 'g'), 80) AS query
  FROM (SELECT 1) one
  LEFT JOIN pg_stat_activity a ON a.usename = current_user AND a.pid <> pg_backend_pid()
\watch i=1 c=$MAX
SQL
# 컨테이너 안 psql 의 pid 를 컨테이너 /tmp 에 적는다 — docker exec 클라이언트를 죽여도 컨테이너 안 프로세스는 남기 때문이다
nohup setsid sudo docker exec -i -e RC_L="$L" "$PG" \
  sh -c 'echo $$ > /tmp/rc-$RC_L-act.pid; exec psql -X -q -t --csv -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < "/tmp/rc-$L-act.sql" > "/tmp/rc-$L-act.csv" 2> "/tmp/rc-$L-act.err" &
echo $! > "/tmp/rc-$L-act.hostpid"
sleep 3
if [ ! -s "/tmp/rc-$L-act.csv" ]; then
  echo "db01 활동 샘플러가 3초 안에 한 줄도 쓰지 않았다:" >&2; cat "/tmp/rc-$L-act.err" >&2; exit 1
fi
REMOTE
}

start_app() {
  ssh_ "$1" bash -s -- "$LABEL" "$MAX_SEC" <<'REMOTE'
set -eu
L=$1 MAX=$2
[ ! -e "/tmp/rc-$L-mem.txt" ] || { echo "$(hostname) /tmp/rc-$L-mem.txt 가 이미 있다 — 같은 LABEL 이 돌고 있거나 end 를 하지 않았다" >&2; exit 1; }
cat > "/tmp/rc-$L-mem.sh" <<'SH'
end=$(( $(date +%s) + $1 ))
while [ "$(date +%s)" -lt "$end" ]; do
  echo "## $(date '+%F %T') MemAvailable_kB=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)"
  sudo docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}'
  sleep 5
done
SH
nohup setsid sh "/tmp/rc-$L-mem.sh" "$MAX" > "/tmp/rc-$L-mem.txt" 2>&1 < /dev/null &
echo $! > "/tmp/rc-$L-mem.pid"
sleep 3
[ -s "/tmp/rc-$L-mem.txt" ] || { echo "$(hostname) 메모리 샘플러가 3초 안에 쓰지 않았다" >&2; exit 1; }
REMOTE
}

stop_db01() {
  ssh_ db01 bash -s -- "$LABEL" "$PG" <<'REMOTE'
set -u
L=$1 PG=$2
sudo docker exec -e RC_L="$L" "$PG" sh -c 'p=$(cat /tmp/rc-$RC_L-act.pid 2>/dev/null) && kill "$p" 2>/dev/null; rm -f /tmp/rc-$RC_L-act.pid'
sleep 1
hp=$(cat "/tmp/rc-$L-act.hostpid" 2>/dev/null || true)
[ -z "$hp" ] || kill "$hp" 2>/dev/null || true
[ ! -s "/tmp/rc-$L-act.err" ] || { echo "db01 샘플러 오류 출력:" >&2; cat "/tmp/rc-$L-act.err" >&2; }
true
REMOTE
}

stop_app() {
  ssh_ "$1" bash -s -- "$LABEL" <<'REMOTE'
set -u
L=$1
p=$(cat "/tmp/rc-$L-mem.pid" 2>/dev/null || true)
# setsid 로 띄운 셸은 제 프로세스 그룹의 우두머리다 — 그룹째 멈춰 sleep · docker stats 자식까지 끝낸다
[ -z "$p" ] || kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true
true
REMOTE
}

# 노드 파일 회수 — 받은 파일이 비지 않았을 때만 노드 쪽을 지운다
fetch() {
  local host=$1 remote=$2 local_=$3
  if ssh_ "$host" "cat $remote" > "$local_" && [ -s "$local_" ]; then
    ssh_ "$host" "rm -f /tmp/rc-$LABEL-*"
  else
    echo "$host $remote 를 받지 못했다 — 노드 파일을 남겨 둔다" >&2
  fi
}

case "$CMD" in
  start)
    echo "SELECT pg_stat_statements_reset();" | psql_ postgres > /dev/null
    snapshot start
    ssh_ db01 "date -u +%Y-%m-%dT%H:%M:%SZ" | tr -d '\r' > "$D/start-utc.txt"
    start_db01
    for h in $APPS; do start_app "$h"; done
    echo "start $LABEL $(cat "$D/start-utc.txt") — 샘플러 db01 · $APPS (상한 ${MAX_SEC}초)"
    ;;

  end)
    [ -s "$D/start-utc.txt" ] || { echo "$D/start-utc.txt 가 없다 — 같은 LABEL · OUTDIR 로 start 했는가" >&2; exit 1; }
    START=$(cat "$D/start-utc.txt")
    stop_db01
    for h in $APPS; do stop_app "$h"; done
    { echo "ts,state,wait_event_type,wait_event,query"; } > "$D/activity.csv"
    fetch db01 "/tmp/rc-$LABEL-act.csv" "$D/activity.raw"
    cat "$D/activity.raw" >> "$D/activity.csv" && rm -f "$D/activity.raw"
    for h in $APPS; do fetch "$h" "/tmp/rc-$LABEL-mem.txt" "$D/mem-$h.txt"; done

    snapshot end
    psql_ postgres > "$D/statements.csv" <<'SQL'
COPY (
  SELECT s.queryid, s.calls, round(s.total_exec_time::numeric, 3) AS total_exec_time, round(s.mean_exec_time::numeric, 4) AS mean_exec_time,
         s.rows, s.shared_blks_hit, s.shared_blks_read, s.temp_blks_written,
         CASE WHEN s.query ~* '^\s*(BEGIN|COMMIT|ROLLBACK|SET|SHOW|RESET|START\s+TRANSACTION|SAVEPOINT|RELEASE|DISCARD)\M' THEN 'txn'
              WHEN s.query ~ 'pg_stat' THEN 'self'
              ELSE 'app' END AS class,
         s.query
    FROM pg_stat_statements s
    JOIN pg_database d ON d.oid = s.dbid
    JOIN pg_roles r ON r.oid = s.userid
   WHERE d.datname = :'appdb' AND r.rolname = current_user
   ORDER BY s.total_exec_time DESC
) TO STDOUT WITH CSV HEADER;
SQL

    # 대기 이벤트 · 차이 · 질의 요약
    "$PY" - "$D" <<'PY'
import csv, os, sys
from collections import Counter, defaultdict
d = sys.argv[1]
def rows(name):
    with open(os.path.join(d, name), encoding='utf-8', newline='') as f:
        return list(csv.reader(f))

def diff(name, nkey):
    a, b = rows(name + '-start.csv'), rows(name + '-end.csv')
    head = b[0]
    old = {tuple(r[:nkey]): r for r in a[1:] if r}
    with open(os.path.join(d, name + '-diff.csv'), 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(head)
        for r in b[1:]:
            k = tuple(r[:nkey])
            if not r or k not in old:
                continue
            v = [int(x or 0) - int(y or 0) for x, y in zip(r[nkey:], old[k][nkey:])]
            if any(v):
                w.writerow(list(k) + v)
diff('tables', 1)
diff('indexes', 2)

st = rows('statements.csv')
h = st[0]; ix = {n: i for i, n in enumerate(h)}
by = defaultdict(lambda: [0, 0, 0.0])
for r in st[1:]:
    c = by[r[ix['class']]]
    c[0] += 1; c[1] += int(r[ix['calls']]); c[2] += float(r[ix['total_exec_time']])
app = [r for r in st[1:] if r[ix['class']] == 'app']
app_ms = sum(float(r[ix['total_exec_time']]) for r in app) or 1.0
with open(os.path.join(d, 'statements-summary.txt'), 'w', encoding='utf-8') as f:
    f.write('class  종류  calls  total_exec_ms\n')
    for k in sorted(by):
        f.write('%-5s  %d  %d  %.1f\n' % (k, by[k][0], by[k][1], by[k][2]))
    f.write('\napp 질의 실행 시간 비중(상위 15, 전체는 statements.csv)\n')
    for r in app[:15]:
        q = ' '.join(r[ix['query']].split())[:120]
        f.write('%5.1f%%  calls=%s  mean_ms=%s  rows=%s  hit=%s  read=%s  %s\n' % (
            100 * float(r[ix['total_exec_time']]) / app_ms, r[ix['calls']], r[ix['mean_exec_time']], r[ix['rows']],
            r[ix['shared_blks_hit']], r[ix['shared_blks_read']], q))

act = [r for r in rows('activity.csv')[1:] if len(r) >= 5]   # \watch 머리글 같은 다른 줄은 뺀다
ticks = len({r[0] for r in act})
active = [r for r in act if len(r) >= 5 and r[1] == 'active']
per_tick = Counter(r[0] for r in active)
waits = Counter((r[2] + ':' + r[3]) if r[2] else 'CPU(대기 없음)' for r in active)
queries = Counter(r[4] for r in active)
states = Counter(r[1] or '(연결 없음)' for r in act)
with open(os.path.join(d, 'activity-summary.txt'), 'w', encoding='utf-8') as f:
    f.write('표본 초 %d · active 행 %d · 초당 평균 active %.2f · 최대 %d\n' % (
        ticks, len(active), len(active) / (ticks or 1), max(per_tick.values(), default=0)))
    f.write('\nstate 행 수\n')
    for k, v in states.most_common():
        f.write('  %6d  %s\n' % (v, k))
    f.write('\nactive 행의 대기 이벤트(비율)\n')
    for k, v in waits.most_common():
        f.write('  %5.1f%%  %6d  %s\n' % (100 * v / (len(active) or 1), v, k))
    f.write('\nactive 행의 질의(앞 80자, 상위 15)\n')
    for k, v in queries.most_common(15):
        f.write('  %5.1f%%  %6d  %s\n' % (100 * v / (len(active) or 1), v, k))
PY

    # auto_explain — 원시 로그는 파일로 남기지 않고 바로 거른다(파라미터 줄 제거)
    AE_PY=$(cat <<'PY'
import re, sys, os
from collections import Counter
d = sys.argv[1]
text = sys.stdin.buffer.read().decode('utf-8', 'replace').splitlines()
head = re.compile(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}')   # 기본 log_line_prefix '%m [%p] ' — 로그 줄 시작
blocks, cur, other_duration = [], None, 0
for line in text:
    if head.match(line):
        if cur is not None:
            blocks.append(cur); cur = None
        if 'duration:' in line and 'plan:' in line:
            cur = [line]
        elif 'duration:' in line:
            other_duration += 1
    elif cur is not None:
        cur.append(line)
if cur is not None:
    blocks.append(cur)
param = re.compile(r'^\s*(Query )?Parameters:')
node = re.compile(r'\((cost|actual)[= ]')
dollar = re.compile(r'\$\d+')
join = re.compile(r'(Nested Loop|Hash(?: \w+)? Join|Merge(?: \w+)? Join)')
generic, joins_blocks, joins_nodes, by_query = 0, Counter(), Counter(), Counter()
with open(os.path.join(d, 'auto-explain.txt'), 'w', encoding='utf-8') as f:
    for b in blocks:
        b = [l for l in b if not param.match(l)]
        f.write('\n'.join(b) + '\n\n')
        start = next((i for i, l in enumerate(b) if node.search(l)), len(b))
        plan = b[start:]
        qi = next((i for i, l in enumerate(b[:start]) if 'Query Text:' in l), None)
        qt = '' if qi is None else ' '.join((b[qi].split('Query Text:', 1)[1] + ' ' + ' '.join(b[qi + 1:start])).split())[:80]
        is_generic = any(dollar.search(l) for l in plan)
        generic += is_generic
        kinds = set()
        for l in plan:
            for m in join.findall(l):
                k = 'Nested Loop' if m.startswith('Nested') else ('Hash Join' if m.startswith('Hash') else 'Merge Join')
                joins_nodes[k] += 1; kinds.add(k)
        for k in kinds:
            joins_blocks[k] += 1
        by_query[(qt, is_generic)] += 1
with open(os.path.join(d, 'auto-explain-summary.txt'), 'w', encoding='utf-8') as f:
    f.write('auto_explain 블록 %d · 계획에 $n 이 있는 블록(일반 계획) %d · 계획 없는 duration 줄 %d\n' % (len(blocks), generic, other_duration))
    f.write('\n조인 방식 — 블록 수 / 노드 수\n')
    for k in ('Nested Loop', 'Hash Join', 'Merge Join'):
        f.write('  %-11s  %6d / %6d\n' % (k, joins_blocks[k], joins_nodes[k]))
    f.write('\n질의별(Query Text 앞 80자) — 블록 수 · 일반 계획 여부\n')
    for (q, g), v in sorted(by_query.items(), key=lambda x: -x[1]):
        f.write('  %6d  %-7s  %s\n' % (v, 'generic' if g else 'custom', q))
PY
)
    # 부하 직후 이 가져오기가 SSH 째로 매달린 적이 있다(9/29 — timeout 도 끊지 못했다). RC_SKIP_AE=1 이면 건너뛰고, 회차 구간(start-utc.txt ~ 끝 시각)으로 나중에 회수한다
    if [ "${RC_SKIP_AE:-0}" = 1 ]; then date -u +%Y-%m-%dT%H:%M:%SZ > "$D/end-utc-local.txt"; else
    ssh_ db01 "sudo docker logs --since $START $PG 2>&1" | "$PY" -c "$AE_PY" "$D"
    fi
    echo "end $LABEL $(date '+%F %T') — $D"
    [ -f "$D/auto-explain-summary.txt" ] && head -n 1 "$D/auto-explain-summary.txt"; true
    head -n 1 "$D/activity-summary.txt"
    ;;

  explain-cmp)
    [ -f "$SQLFILE" ] || { echo "$SQLFILE 가 없다" >&2; exit 2; }
    EXEC=$(sed -n 's/^-- EXEC:[[:space:]]*//p' "$SQLFILE" | tr -d '\r')
    [ -n "$EXEC" ] || { echo "$SQLFILE 에 「-- EXEC: EXECUTE q(…);」 줄이 없다" >&2; exit 2; }
    for mode in custom generic planner; do
      case $mode in
        custom)  SETS="SET LOCAL plan_cache_mode = force_custom_plan;" ;;
        generic) SETS="SET LOCAL plan_cache_mode = force_generic_plan;" ;;
        planner) SETS="SET LOCAL plan_cache_mode = force_custom_plan; SET LOCAL effective_cache_size = '512MB'; SET LOCAL random_page_cost = 1.1;" ;;
      esac
      { echo "BEGIN READ ONLY;"; echo "$SETS"; tr -d '\r' < "$SQLFILE"; echo
        echo "\\o /dev/null"; echo "$EXEC"; echo "\\o"
        echo "EXPLAIN (ANALYZE, BUFFERS) $EXEC"; echo "ROLLBACK;"; } | psql_app > "$D/explain-$mode.txt"
    done
    echo "explain-cmp $LABEL — $D/explain-custom.txt · explain-generic.txt · explain-planner.txt"
    ;;

  *) echo "start · end · explain-cmp" >&2; exit 2 ;;
esac
