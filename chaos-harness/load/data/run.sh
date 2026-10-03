#!/usr/bin/env bash
# 부하 시험 데이터 운영 반영 (#376). DB-01 에서 deploy 로 실행한다 — 운영 Compose 의 postgres 컨테이너에 psql 로 붙는다.
#
#   ./run.sh stage                      # 스키마 · 적재용 표 · 기준선(반영 전 최대 식별자 · LSN)
#   ./run.sh copy-property <파일.gz>... # 생성기 매물 출력 → loadtest.stage_property
#   ./run.sh template                   # 판정 원본 풀 · 건물 실대장
#   ./run.sh district <구> <fake_limit> # 그 구의 매물 → 판정 묶음 → 이력, 끝나면 standby 따라잡기를 기다린다
#   ./run.sh all <fake_limit> [구...]   # 위를 25개 구(또는 준 구)에 차례로
#   ./run.sh copy-users <users.gz> <user_auth.gz> ; ./run.sh users
#   ./run.sh verify
#
# 멈춤 조건: 복제 슬롯이 붙잡은 WAL 이 SLOT_PAUSE_BYTES(상한 2GB 의 절반)를 넘으면 줄 때까지 기다린다.
set -euo pipefail
cd "$(dirname "$0")"
INFRA_DIR=${INFRA_DIR:-$HOME/rental/infra}
SLOT_PAUSE_BYTES=${SLOT_PAUSE_BYTES:-1073741824}
DISTRICTS=(종로구 중구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 강서구
           구로구 금천구 영등포구 동작구 관악구 서초구 강남구 송파구 강동구)

psql_() {   # psql_ [psql 인자...] — 표준 입력을 그대로 넘긴다
  docker compose --project-directory "$INFRA_DIR" exec -T postgres \
    sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}
log() { echo "[$(date '+%F %T')] $*"; }

wait_standby() {
  while :; do
    held=$(echo "SELECT coalesce(max(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)), 0)::bigint
                   FROM pg_replication_slots;" | psql_ -At)
    lag=$(echo "SELECT coalesce(max(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)), 0)::bigint
                  FROM pg_stat_replication;" | psql_ -At)
    if (( held < SLOT_PAUSE_BYTES && lag < 67108864 )); then
      log "standby ok — slot_held=${held} replay_lag_bytes=${lag}"; return
    fi
    log "standby 대기 — slot_held=${held} replay_lag_bytes=${lag}"; sleep 15
  done
}

disk_guard() {   # 데이터 볼륨이 DISK_STOP_PCT 를 넘으면 멈춘다 — 20GB 볼륨에 500만 건이 ≈ 14 ~ 15GB 로 들어간다(#376 종로구 실측)
  local used; used=$(df --output=pcent /srv/pgdata | tail -1 | tr -dc '0-9')
  if (( used >= ${DISK_STOP_PCT:-88} )); then log "디스크 ${used}% — 멈춘다"; exit 3; fi
}

district() {
  local d=$1 limit=$2 t0=$SECONDS
  disk_guard
  log "== $d 매물"
  psql_ -v district="$d" -v fake_limit="$limit" < sql/10_property.sql
  log "== $d 판정 묶음"
  psql_ -v district="$d" < sql/20_bundle.sql
  log "== $d 이력"
  psql_ -v district="$d" < sql/30_history.sql
  log "== $d 끝 ($((SECONDS - t0))s)"
  wait_standby
}

case "${1:-}" in
  stage)    psql_ < sql/00_stage.sql; echo "SELECT * FROM loadtest.baseline;" | psql_ ;;
  copy-property)
    shift
    for f in "$@"; do
      log "copy $f"
      zcat "$f" | psql_ -c "\copy loadtest.stage_property (kind, seq, address, district, landlord_name, contract_type,
        property_type, deposit, monthly_rent, market_price, price_type, price_date, area_sqm, floor, built_year,
        latitude, longitude, sigungu_code, bjdong_code, bun, ji, registry_owner) FROM STDIN (FORMAT csv, HEADER true, NULL '')"
    done
    echo "CREATE INDEX IF NOT EXISTS stage_property_district ON loadtest.stage_property (district, kind, seq);
          ANALYZE loadtest.stage_property;
          SELECT kind, count(*) FROM loadtest.stage_property GROUP BY kind;" | psql_ ;;
  template) psql_ < sql/15_template.sql ;;
  district) district "$2" "$3" ;;
  all)
    limit=$2; shift 2
    targets=("$@"); (( ${#targets[@]} )) || targets=("${DISTRICTS[@]}")
    for d in "${targets[@]}"; do district "$d" "$limit"; done ;;
  copy-users)
    zcat "$2" | psql_ -c "\copy loadtest.stage_users FROM STDIN (FORMAT csv, NULL '')"
    zcat "$3" | psql_ -c "\copy loadtest.stage_user_auth FROM STDIN (FORMAT csv, NULL '')" ;;
  users)    psql_ < sql/40_users.sql; wait_standby ;;
  pick)     psql_ < sql/41_pick.sql ;;
  notify)   psql_ < sql/42_notification.sql; wait_standby ;;
  owner-fix) psql_ < sql/50_owner_mismatch.sql; wait_standby ;;
  wishlist)   # wishlist <첫 user_id> <끝 user_id> [구간 크기] — 구간마다 커밋, 매물 식별자 순서로 넣는다(41_wishlist.sql)
    from=$2; to=$3; step=${4:-50000}
    for ((a = from; a <= to; a += step)); do
      b=$(( a + step - 1 )); (( b > to )) && b=$to
      t0=$SECONDS
      psql_ -v from_user="$a" -v to_user="$b" < sql/41_wishlist.sql
      log "wishlist $a ~ $b ($((SECONDS - t0))s)"
      disk_guard
    done
    wait_standby ;;
  verify)   psql_ < sql/90_verify.sql ;;
  analyze)  echo "ANALYZE property; ANALYZE risk_analysis; ANALYZE building_registry; ANALYZE building_ledger;
                  ANALYZE ownership_history; ANALYZE mortgage_history; ANALYZE users; ANALYZE user_auth; ANALYZE wishlist;
                  ANALYZE notification; ANALYZE wishlist_notification; ANALYZE notification_subscription;" | psql_ ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
