#!/usr/bin/env bash
# 회차 전 상태 기록과 질의 통계 초기화(INF-06 #378). record.sh start 바로 전에 부른다.
#
#   bash pre-round.sh <회차> [--no-reset]
#
# 회차의 결과는 데이터 상태 · 캐시 상태 · 크레딧에 따라 달라진다. 회차마다 같은 항목을 같은 방법으로 남겨, 회차끼리 비교할 때
# 「조건이 같았는가」를 숫자로 본다. 기록만 하고 고치지 않는다 — 경고가 나오면 사람이 판단한다.
#
#   DB 노드마다(DB-01 · DB-02)  주요 테이블 행수(n_live_tup · reltuples) · 가시성 맵 비율(relallvisible / relpages) ·
#                               DB 크기 · 인덱스 총 크기 · 버퍼 적중률(pg_stat_database) · 도는 자동 청소 · 복제 상태
#   앱 노드마다                  슬롯(app-1 · app-2) 상태 · 배치 스위치 넷(노드 .env 와 도는 슬롯의 환경)
#   CloudWatch                  크레딧 · EBS 버스트(credits.sh)
#   Redis                       INFO commandstats(회차 뒤 값과의 차가 회차의 Redis 명령 수)
#   그리고 두 DB 의 pg_stat_statements_reset() — 회차의 질의 통계만 남게. --no-reset 이면 건너뛴다
#
# 결과 — results/<회차>/pre/
#   pre.json                    아래 「pre.json 모양」
#   db01-db.json · db02-db.json DB 노드별 원자료(pre.json 의 db.<노드>와 같다)
#   slots.txt · batch-flags.txt 원자료
#   credits.json                credits.sh 형식
#   redis-commandstats.txt      첫 줄 「# captured_utc <시각>」 뒤에 redis-cli 출력 그대로
#
# 서버 상태를 바꾸는 것은 질의 통계 초기화 하나다(통계 뷰의 누적값만 지운다 — 서비스와 무관).
# 배치 스위치는 **확인만 한다** — 키 네 줄 밖의 .env 내용은 노드 밖으로 나오지 않는다(노드에서 grep 으로 거른 뒤 보낸다).
#   CREDIT_MIN   이 값보다 크레딧 잔고가 작은 노드를 경고한다(기본 비어 있음 — 값만 보인다)
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

ROUND=${1:-}
[ -n "$ROUND" ] || { echo "사용법: bash pre-round.sh <회차> [--no-reset]" >&2; exit 2; }
check_round "$ROUND"
RESET=1
case ${2:-} in
  "") ;;
  --no-reset) RESET=0 ;;
  *) echo "사용법: bash pre-round.sh <회차> [--no-reset]" >&2; exit 2 ;;
esac
RD=$(round_dir "$ROUND")
PRE=$RD/pre
mkdir -p "$PRE"
WARNINGS=()
note() { WARNINGS+=("$*"); warn "$*"; }

BATCH_KEYS="PROPERTY_BATCH_REFRESH_ENABLED RISK_BATCH_REGISTRYREFRESH_ENABLED RISK_BATCH_MOCKLEDGERREPLACE_ENABLED BATCH_STARTUPCATCHUP_ENABLED"
TABLES="property risk_analysis ownership_history building_registry mortgage_history building_ledger users wishlist notification_subscription"

# ── DB 상태 — 한 질의가 JSON 한 줄을 낸다 ─────────────────────────────────────────────────
# standby(DB-02)에서 n_live_tup 은 0 이다 — 테이블 통계는 노드마다 따로 쌓이고 복제되지 않는다. 행수는 reltuples(카탈로그 —
# 복제된다, 마지막 ANALYZE 시점의 추정)로 본다. 가시성 맵 비율도 카탈로그 값(relallvisible · relpages)이라 두 노드가 같다.
# replay_lag_seconds 는 마지막으로 재생한 트랜잭션 이후 시간이라 쓰기가 없으면 지연이 없어도 커진다 — 바이트 차를 함께 본다
db_sql() {
  local arr="" t s=""
  for t in $TABLES; do arr="$arr$s'$t'"; s=","; done
  cat <<SQL
WITH t(name) AS (SELECT unnest(ARRAY[$arr]::text[])),
tbl AS (
  SELECT t.name, s.n_live_tup, s.n_dead_tup, c.reltuples::bigint AS reltuples, c.relpages, c.relallvisible,
         round(c.relallvisible::numeric / nullif(c.relpages, 0), 4) AS vm_ratio,
         s.last_autovacuum, s.last_autoanalyze, s.last_analyze
  FROM t
  LEFT JOIN pg_class c ON c.relname = t.name AND c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
  LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
)
SELECT json_build_object(
  'captured_utc', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  'in_recovery', pg_is_in_recovery(),
  'db_size_bytes', pg_database_size(current_database()),
  'index_size_bytes', (SELECT coalesce(sum(pg_relation_size(indexrelid)), 0) FROM pg_stat_user_indexes),
  'blks_hit', d.blks_hit,
  'blks_read', d.blks_read,
  'buffer_hit_ratio', round(d.blks_hit::numeric / nullif(d.blks_hit + d.blks_read, 0), 6),
  'stats_reset', d.stats_reset,
  'tables', (SELECT json_object_agg(name, json_build_object(
                'n_live_tup', n_live_tup, 'n_dead_tup', n_dead_tup, 'reltuples', reltuples,
                'relpages', relpages, 'relallvisible', relallvisible, 'vm_ratio', vm_ratio,
                'last_autovacuum', last_autovacuum, 'last_autoanalyze', last_autoanalyze, 'last_analyze', last_analyze))
             FROM tbl),
  'autovacuum_running', (SELECT coalesce(json_agg(json_build_object('pid', p.pid, 'relation', p.relid::regclass::text, 'phase', p.phase)), '[]'::json)
                         FROM pg_stat_progress_vacuum p),
  'autovacuum_workers', (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'autovacuum worker'),
  'replication', (SELECT coalesce(json_agg(json_build_object('client_addr', host(r.client_addr), 'state', r.state,
                     'replay_lag_seconds', extract(epoch FROM r.replay_lag),
                     'replay_lag_bytes', pg_wal_lsn_diff(pg_current_wal_lsn(), r.replay_lsn))), '[]'::json)
                  FROM pg_stat_replication r WHERE NOT pg_is_in_recovery()),
  'replay_lag_seconds', CASE WHEN pg_is_in_recovery() THEN extract(epoch FROM now() - pg_last_xact_replay_timestamp()) END,
  'receive_replay_lag_bytes', CASE WHEN pg_is_in_recovery() THEN pg_wal_lsn_diff(pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn()) END
)
FROM pg_stat_database d WHERE d.datname = current_database();
SQL
}

DB_JSON=""
for node in $DB_NODES; do
  log "[$(node_label "$node")] DB 상태"
  if out=$(db_sql | psql_node "$node") && [ -n "$out" ]; then
    printf '%s\n' "$out" > "$PRE/$node-db.json"
  else
    note "$(node_label "$node") DB 상태를 읽지 못했다 — pre.json 에 null 로 남긴다"
    out=null
    printf 'null\n' > "$PRE/$node-db.json"
  fi
  DB_JSON="$DB_JSON${DB_JSON:+,
    }\"$node\": $out"
  # 경고 — 도는 자동 청소(회차 동안 IO · CPU 를 같이 쓴다), 행수 0(데이터가 빠졌다)
  if [ "$out" != null ]; then
    if ! printf '%s' "$out" | grep -q '"autovacuum_running" : \[\]' && printf '%s' "$out" | grep -q '"autovacuum_running"'; then
      note "$(node_label "$node") 자동 청소가 돌고 있다 — 끝난 뒤 시작하는 것을 검토한다(${PRE}/$node-db.json 의 autovacuum_running)"
    fi
  fi
done

# ── 슬롯 상태 · 배치 스위치 — 앱 노드마다 ─────────────────────────────────────────────────
SLOTS_JSON="" FLAGS_JSON=""
: > "$PRE/slots.txt"; : > "$PRE/batch-flags.txt"
for node in $APP_NODES; do
  log "[$(node_label "$node")] 슬롯 · 배치 스위치"
  KEYS_RE=$(echo "$BATCH_KEYS" | tr ' ' '|')
  # 노드에서 키 네 줄만 걸러 보낸다. 도는 슬롯의 환경(docker inspect)도 같은 키만 — .env 를 바꾸고 재생성하지 않았으면 둘이 다르다
  if ! raw=$(on_node "$node" "bash -s -- $(q "$NODE_DIR") $(q "$KEYS_RE")" <<'EOF'
set -u
cd "$1" || exit 1
for s in app-1 app-2; do
  id=$(docker compose ps -q "$s" 2>/dev/null)
  if [ -z "$id" ]; then echo "SLOT $s missing"; continue; fi
  echo "SLOT $s $(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
done
echo "SECTION env_file"
grep -E "^($2)=" .env 2>/dev/null | tr -d '\r'
for s in app-1 app-2; do
  id=$(docker compose ps -q "$s" 2>/dev/null)
  [ -n "$id" ] || continue
  echo "SECTION $s"
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$id" | grep -E "^($2)="
done
exit 0
EOF
  ); then
    note "$(node_label "$node") 에 닿지 못했다 — 슬롯 · 배치 스위치를 null 로 남긴다"
    SLOTS_JSON="$SLOTS_JSON${SLOTS_JSON:+, }\"$node\": null"
    FLAGS_JSON="$FLAGS_JSON${FLAGS_JSON:+,
    }\"$node\": null"
    continue
  fi
  printf '[%s]\n%s\n' "$node" "$(printf '%s\n' "$raw" | grep '^SLOT ')" >> "$PRE/slots.txt"
  printf '[%s]\n%s\n' "$node" "$(printf '%s\n' "$raw" | grep -v '^SLOT ')" >> "$PRE/batch-flags.txt"

  # 슬롯
  sj=""
  while read -r _ s st; do
    sj="$sj${sj:+, }\"$s\": $(json_str "$st")"
    [ "$st" = healthy ] || note "$(node_label "$node") $s 가 healthy 가 아니다($st) — 슬롯 넷이 다 살아 있어야 회차 조건이 같다"
  done < <(printf '%s\n' "$raw" | grep '^SLOT ')
  SLOTS_JSON="$SLOTS_JSON${SLOTS_JSON:+, }\"$node\": {$sj}"

  # 배치 스위치 — 구역(env_file · app-1 · app-2)마다 키 넷. 줄이 없으면 "unset"(앱 기본값 true — 배치가 돈다)
  fj=""
  for sec in env_file app-1 app-2; do
    if ! printf '%s\n' "$raw" | grep -qx "SECTION $sec"; then continue; fi
    body=$(printf '%s\n' "$raw" | awk -v s="SECTION $sec" '$0 == s { on = 1; next } /^SECTION / { on = 0 } on')
    kj="" bad=""
    for k in $BATCH_KEYS; do
      v=$(printf '%s\n' "$body" | { grep -E "^$k=" || true; } | tail -1 | cut -d= -f2- | tr -d "\"' ")
      v=${v:-unset}
      kj="$kj${kj:+, }\"$k\": $(json_str "$v")"
      [ "$v" = false ] || bad="$bad $k=$v"
    done
    # 줄이 없으면(unset) 앱 기본값 true 다
    if [ -n "$bad" ]; then
      note "$(node_label "$node") $sec 배치가 꺼져 있지 않다:$bad — 시험 동안 넷을 끈다(.env.example 의 배치 주석). 확인만 하고 바꾸지 않았다"
    fi
    fj="$fj${fj:+, }\"$sec\": {$kj}"
  done
  FLAGS_JSON="$FLAGS_JSON${FLAGS_JSON:+,
    }\"$node\": {$fj}"
done

# ── 크레딧 ─────────────────────────────────────────────────────────────────────────────
CREDITS_FILE=null
log "크레딧(CloudWatch)"
if bash "$NODE_SCRIPTS/credits.sh" "$PRE/credits.json"; then
  CREDITS_FILE='"credits.json"'
  while IFS= read -r line; do
    name=$(printf '%s' "$line" | sed -n 's/^ *"\([A-Z0-9-]*\)": {.*/\1/p')
    bal=$(printf '%s' "$line" | sed -n 's/.*"cpu_credit_balance": \([^,]*\),.*/\1/p')
    [ -n "$name" ] || continue
    log "    $name 크레딧 잔고 $bal"
    if [ -n "${CREDIT_MIN:-}" ] && [ "$bal" != null ] && awk -v b="$bal" -v m="$CREDIT_MIN" 'BEGIN { exit !(b < m) }'; then
      note "$name 크레딧 잔고 $bal < CREDIT_MIN $CREDIT_MIN — 초과 사용(unlimited 과금) 구간에서 회차가 돌 수 있다"
    fi
  done < <(grep '"cpu_credit_balance"' "$PRE/credits.json")
else
  note "크레딧을 기록하지 못했다(aws CLI · 권한) — pre.json 의 credits_file 은 null"
fi

# ── Redis 명령 통계 ───────────────────────────────────────────────────────────────────
log "Redis 명령 통계"
if ! { echo "# captured_utc $(utc_now_ms)"; redis_commandstats; } > "$PRE/redis-commandstats.txt"; then
  note "Redis 명령 통계를 받지 못했다"
fi

# ── 질의 통계 초기화 — 클러스터 전체에 걸린다(두 노드 각각) ─────────────────────────────────
RESET_JSON=""
for node in $DB_NODES; do
  if [ "$RESET" -eq 0 ]; then
    RESET_JSON="$RESET_JSON${RESET_JSON:+, }\"$node\": false"; continue
  fi
  db=$(pgss_db "$node")
  if printf 'SELECT pg_stat_statements_reset();\n' | psql_node "$node" "$db" > /dev/null; then
    log "[$(node_label "$node")] pg_stat_statements_reset() — ${db:-앱 DB} 에서"
    RESET_JSON="$RESET_JSON${RESET_JSON:+, }\"$node\": true"
  else
    note "$(node_label "$node") 질의 통계를 초기화하지 못했다 — 회차의 질의 통계에 이전 누적이 섞인다"
    RESET_JSON="$RESET_JSON${RESET_JSON:+, }\"$node\": false"
  fi
done
RESET_UTC=$(utc_now_ms)

# ── pre.json ─────────────────────────────────────────────────────────────────────────
WJ=""
for w in "${WARNINGS[@]+"${WARNINGS[@]}"}"; do WJ="$WJ${WJ:+, }$(json_str "$w")"; done
cat > "$PRE/pre.json" <<EOF
{
  "round": "$ROUND",
  "ts": "$(utc_now_ms)",
  "pgss_reset": {$RESET_JSON},
  "pgss_reset_utc": "$RESET_UTC",
  "db": {
    $DB_JSON
  },
  "slots": {$SLOTS_JSON},
  "batch_flags": {
    $FLAGS_JSON
  },
  "credits_file": $CREDITS_FILE,
  "warnings": [$WJ]
}
EOF

echo
log "회차 $ROUND 전 상태 — $PRE/pre.json"
for node in $DB_NODES; do
  f=$PRE/$node-db.json
  [ -s "$f" ] && [ "$(cat "$f")" != null ] || continue
  size=$(sed -n 's/.*"db_size_bytes" : \([0-9]*\).*/\1/p' "$f")
  idx=$(sed -n 's/.*"index_size_bytes" : \([0-9]*\).*/\1/p' "$f")
  hit=$(sed -n 's/.*"buffer_hit_ratio" : \([0-9.]*\).*/\1/p' "$f")
  prop=$(sed -n 's/.*"property" : {"n_live_tup" : \([0-9]*\), "n_dead_tup" : [0-9null]*, "reltuples" : \([0-9-]*\).*/n_live_tup \1 · reltuples \2/p' "$f")
  log "  $(node_label "$node")  DB $((${size:-0} / 1048576)) MiB · 인덱스 $((${idx:-0} / 1048576)) MiB · 적중률 ${hit:-?} · property ${prop:-?}"
done
if [ "${#WARNINGS[@]}" -eq 0 ]; then
  log "경고 없음"
else
  log "경고 ${#WARNINGS[@]}건 — 위 !!! 줄. 회차를 시작할지 사람이 정한다"
fi
