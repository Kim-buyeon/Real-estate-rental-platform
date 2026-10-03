#!/usr/bin/env bash
# 회차 뒤 수집 — 질의 통계 · auto_explain · (변화 회차면) 설정 · 인덱스 · 테이블 통계 · 추적 파일(INF-06 #378).
#
#   bash collect.sh <회차> [--heavy]
#
# record.sh stop 뒤에 부른다 — 구간(start_utc ~ end_utc)을 results/<회차>/meta.json 에서 읽는다.
# 추적은 슬롯 에이전트가 몇 초씩 묶어 보내므로 회차가 끝나고 **10초 이상 지난 뒤** 부른다.
#
# 결과 — results/<회차>/
#   db/<노드>-pgss.csv            앱 DB 질의 상위 20(총 실행 시간 순). 머리
#                                 queryid,calls,total_exec_time_ms,mean_exec_time_ms,rows,shared_blks_hit,shared_blks_read,
#                                 temp_blks_written,blk_read_time_ms,query
#                                 query 는 공백 · 줄바꿈을 한 칸으로 줄여 앞 300자. blk_read_time_ms 는 PG 17 의 shared_blk_read_time
#                                 (16 까지의 blk_read_time 이 17 에서 shared · local 로 나뉘었다)
#   db/<노드>-pgss-all.csv.gz     같은 뷰 전체(모든 DB · 모든 열 · 원문 질의). gzip 한 CSV, 머리 있음, 첫 열 datname
#                                 (dbid 의 DB 이름 — 이어서 pg_stat_statements 의 전 열 그대로). 집계가 이 이름 · 형식으로 읽는다
#   db/<노드>-dbstats.csv         회차 뒤 pg_stat_database 한 줄(앱 DB) — pre.json 의 blks_hit · blks_read 와 빼서 회차의 적중률
#   db/<노드>-auto-explain.log    구간의 DB 컨테이너 로그 중 auto_explain 덩어리(「duration:」 과 「plan:」 이 든 항목)만
#   heavy/<노드>-settings.csv     (--heavy) pg_settings 의 name,setting,unit,source
#   heavy/<노드>-indexes.csv      (--heavy) 인덱스별 스캔 · 크기 · 블록 적중/읽기
#   heavy/<노드>-tables.csv       (--heavy) pg_stat_user_tables 전 열 + relpages · reltuples · relallvisible · total_bytes
#   traces/spans.jsonl            부하 생성기의 추적 수신기 파일(trace-receiver.sh). 옮긴 뒤 수신기 쪽 파일은 비운다
#
# 읽기만 한다 — 서버 상태를 바꾸지 않는다(질의 통계도 지우지 않는다. 지우는 것은 다음 회차의 pre-round.sh).
#   TRACE_DIR   수신기 파일 자리(기본 chaos-harness/measure/trace-data — trace-receiver.sh 와 같다)
#   TRACE_FILE  파일을 직접 짚을 때(기본 $TRACE_DIR/spans.jsonl)
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() { echo "사용법: bash collect.sh <회차> [--heavy]" >&2; exit 2; }
ROUND=${1:-}; [ -n "$ROUND" ] || usage
check_round "$ROUND"
HEAVY=0
case ${2:-} in "") ;; --heavy) HEAVY=1 ;; *) usage ;; esac
RD=$(round_dir "$ROUND")
[ -f "$RD/meta.json" ] || die "meta.json 이 없다: $RD — record.sh start · stop 을 먼저 한다"
START=$(sed -n 's/.*"start_utc": "\([^"]*\)".*/\1/p' "$RD/meta.json")
END=$(sed -n 's/.*"end_utc": "\([^"]*\)".*/\1/p' "$RD/meta.json")
[ -n "$START" ] || die "meta.json 에 start_utc 가 없다"
[ -n "$END" ] || die "meta.json 에 end_utc 가 없다 — record.sh stop $ROUND 을 먼저 한다"
TRACE_DIR=${TRACE_DIR:-$MEASURE_ROOT/trace-data}
TRACE_FILE=${TRACE_FILE:-$TRACE_DIR/spans.jsonl}
mkdir -p "$RD/db"

save() {  # <설명> <파일> — 앞 명령의 표준 출력을 받은 파일의 크기를 보인다
  log "  $(fsize "$2") B  $2  ($1)"
}

for node in $DB_NODES; do
  L=$(node_label "$node")
  log "[$L] 질의 통계"
  db=$(pgss_db "$node")
  if [ -n "$db" ]; then log "    앱 DB 에 확장이 없다 — $db DB 의 뷰에서 앱 DB(dbid)만 읽는다"; fi
  # COPY … TO STDOUT 은 CSV 따옴표 · 줄바꿈을 규칙대로 감싼다. :'appdb' 는 psql_node 가 넘기는 앱 DB 이름
  if psql_node "$node" "$db" > "$RD/db/$node-pgss.csv" <<'SQL'
COPY (
  SELECT s.queryid,
         s.calls,
         round(s.total_exec_time::numeric, 3)      AS total_exec_time_ms,
         round(s.mean_exec_time::numeric, 3)       AS mean_exec_time_ms,
         s.rows,
         s.shared_blks_hit,
         s.shared_blks_read,
         s.temp_blks_written,
         round(s.shared_blk_read_time::numeric, 3) AS blk_read_time_ms,
         left(regexp_replace(s.query, '\s+', ' ', 'g'), 300) AS query
  FROM pg_stat_statements s
  WHERE s.dbid = (SELECT oid FROM pg_database WHERE datname = :'appdb')
  ORDER BY s.total_exec_time DESC
  LIMIT 20
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
  then save "상위 20" "$RD/db/$node-pgss.csv"; else warn "  [$L] 질의 통계 상위 20 실패"; fi

  if psql_node "$node" "$db" <<'SQL' | gzip -c > "$RD/db/$node-pgss-all.csv.gz"
COPY (
  SELECT d.datname, s.*
  FROM pg_stat_statements s LEFT JOIN pg_database d ON d.oid = s.dbid
  ORDER BY s.total_exec_time DESC
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
  then save "전체" "$RD/db/$node-pgss-all.csv.gz"; else warn "  [$L] 질의 통계 전체 실패"; fi

  if psql_node "$node" > "$RD/db/$node-dbstats.csv" <<'SQL'
COPY (
  SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS captured_utc,
         datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched,
         temp_files, temp_bytes, deadlocks, blk_read_time, blk_write_time, stats_reset
  FROM pg_stat_database WHERE datname = current_database()
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
  then save "pg_stat_database" "$RD/db/$node-dbstats.csv"; else warn "  [$L] pg_stat_database 실패"; fi

  # auto_explain — 로그는 DB 컨테이너의 표준 오류(json-file)다. 구간은 docker 의 기록 시각(UTC)으로 자른다.
  # 한 항목은 「YYYY-MM-DD HH:MM:SS.mmm KST [pid] LOG: …」 줄에서 시작해 다음 그런 줄 전까지다(log_line_prefix 기본 '%m [%p] ',
  # log_timezone Asia/Seoul — 운영 Compose 의 x-postgres-command). 계획 본문은 머리 없는 이어지는 줄이다.
  # json-file 은 10m × 3 에서 회전하므로 긴 회차 · 낮은 문턱이면 앞부분이 잘릴 수 있다 — 회차 직후 부른다
  log "[$L] auto_explain"
  svc=$(db_service "$node")
  if on_node "$node" "cd $(q "$NODE_DIR") && id=\$(docker compose ps -q $svc) && docker logs --since $(q "$START") --until $(q "$END") \"\$id\" 2>&1" \
      | awk '
          /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]/ { flush(); buf = $0; next }
          { if (buf != "") buf = buf "\n" $0 }
          function flush() { if (buf ~ /duration: / && buf ~ /plan:/) print buf; buf = "" }
          END { flush() }' > "$RD/db/$node-auto-explain.log"; then
    n=$(grep -c 'duration: ' "$RD/db/$node-auto-explain.log" || true)
    save "auto_explain ${n}건" "$RD/db/$node-auto-explain.log"
  else
    warn "  [$L] 컨테이너 로그를 읽지 못했다"
  fi

  if [ "$HEAVY" -eq 1 ]; then
    mkdir -p "$RD/heavy"
    log "[$L] 설정 · 인덱스 · 테이블(--heavy)"
    psql_node "$node" > "$RD/heavy/$node-settings.csv" <<'SQL' && save settings "$RD/heavy/$node-settings.csv" || warn "  [$L] pg_settings 실패"
COPY (SELECT name, setting, unit, source FROM pg_settings ORDER BY name) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
    psql_node "$node" > "$RD/heavy/$node-indexes.csv" <<'SQL' && save indexes "$RD/heavy/$node-indexes.csv" || warn "  [$L] 인덱스 통계 실패"
COPY (
  SELECT s.schemaname, s.relname, s.indexrelname, s.idx_scan, s.last_idx_scan, s.idx_tup_read, s.idx_tup_fetch,
         pg_relation_size(s.indexrelid) AS size_bytes, io.idx_blks_read, io.idx_blks_hit
  FROM pg_stat_user_indexes s
  LEFT JOIN pg_statio_user_indexes io ON io.indexrelid = s.indexrelid
  ORDER BY s.relname, s.indexrelname
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
    psql_node "$node" > "$RD/heavy/$node-tables.csv" <<'SQL' && save tables "$RD/heavy/$node-tables.csv" || warn "  [$L] 테이블 통계 실패"
COPY (
  SELECT s.*, c.relpages, c.reltuples, c.relallvisible, pg_total_relation_size(s.relid) AS total_bytes
  FROM pg_stat_user_tables s JOIN pg_class c ON c.oid = s.relid
  ORDER BY s.relname
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
  fi
done

# ── 추적 파일 ─────────────────────────────────────────────────────────────────────────
# 수신기(파일 내보내기, append: true)는 파일을 O_APPEND 로 열어 둔다. 옮기면(mv) 수신기가 옮긴 파일에 계속 쓰므로
# 복사한 뒤 원본을 비운다 — 비운 뒤의 쓰기는 파일 앞부터 다시 쌓인다(0.136.0 에서 로컬 확인 — 빈 바이트 앞머리 없음, 줄 유실 · 중복 없음.
# otelcol.yaml 의 append 주석). 복사와 비우기 사이에 들어온 구간은 잃는다(회차 뒤라 없거나 몇 개)
if [ -f "$TRACE_FILE" ]; then
  mkdir -p "$RD/traces"
  if [ -s "$RD/traces/spans.jsonl" ]; then
    warn "추적 — $RD/traces/spans.jsonl 이 이미 있다. 덮지 않고 이어 붙인다"
  fi
  cat "$TRACE_FILE" >> "$RD/traces/spans.jsonl"
  : > "$TRACE_FILE"
  save "추적(수신기 파일을 비웠다)" "$RD/traces/spans.jsonl"
else
  warn "추적 파일이 없다: $TRACE_FILE — 측정 모드 · 수신기가 꺼져 있었거나 다른 기계다(TRACE_FILE 로 짚는다)"
fi

log "수집 끝 — 다음은 집계(python -m analyze $ROUND)"
