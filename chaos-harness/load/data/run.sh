#!/usr/bin/env bash
# 부하 시험 데이터 운영 반영 (#376). DB-01 에서 deploy 로 실행한다 — 운영 Compose 의 postgres 컨테이너에 psql 로 붙는다.
#
#   ./run.sh stage                      # 스키마 · 적재용 표 · 기준선(반영 전 최대 식별자 · LSN)
#   ./run.sh copy-property <파일.gz>... # 생성기 매물 출력 → loadtest.stage_property
#   ./run.sh plan [목표 총수]           # 실매물 상한 고르기(05_plan.sql) — 기본 100만, 자치구 비율 · 고정 씨앗. 반영 시작 전에 한 번
#   ./run.sh template                   # 판정 원본 풀 · 건물 실대장
#   ./run.sh fingerprint                # 판정 기준 지문(53_fingerprint.sql) — 앱이 적은 지문과 다르면 멈춘다
#   ./run.sh district <구>              # 그 구의 매물 → 판정 묶음(대장 100%) → 결론 · 근거 · 지문 · 매물 열 → 이력, 끝나면 standby 대기
#   ./run.sh all [구...]                # 위를 25개 구(또는 준 구)에 차례로. 가짜는 FAKE_LIMIT(기본 0)
#   ./run.sh ledger-fill LO HI [STEP]   # 기준선 매물 중 대장 없는 것에 MOCK 대장(25_ledger_fill.sql)
#   ./run.sh snapshot-baseline LO HI [STEP]  # 기준선 최신 판정에 근거 · 지문(54 new_rows=false) — 결론이 앱 산식과 같은 행만
#   ./run.sh drop-stage                 # 적재용 표 지우기(매물 반영 끝)
#   ./run.sh copy-users <users.gz> <user_auth.gz> ; ./run.sh users   # 사용자 총수 USERS_TOTAL(기본 30만)
#   ./run.sh pick ; ./run.sh wishlist FROM TO [STEP] ; ./run.sh test-wishlist
#   ./run.sh notify FROM TO [STEP] ; ./run.sh notify-test
#   ./run.sh vacuum                     # VACUUM (ANALYZE) — 반영 끝에 한 번
#   ./run.sh verify
#   ./run.sh guarantee-check [new] [LO HI]      # 보증 3사 · 등급 SQL 재현을 저장값과 대조(읽기 전용). 기준선은 불일치 0 이어야 한다
#   ./run.sh guarantee-recalc LO HI [STEP]     # (#376) 새 매물의 최신 판정을 앱 산식으로 고친다 — 100만 순서에서는 district 가 54 로 한다
#   ./run.sh guarantee-check-all LO HI [STEP]  # 새 매물 대조를 구간마다(기본 50만) — 보정 뒤 전체 확인
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
  local d=$1 limit=${2:-${FAKE_LIMIT:-0}} t0=$SECONDS range lo hi
  disk_guard
  log "== $d 매물"
  psql_ -v district="$d" -v fake_limit="$limit" < sql/10_property.sql
  log "== $d 판정 묶음"
  psql_ -v district="$d" < sql/20_bundle.sql
  # 이 구의 새 매물 식별자 구간 — 10_property 가 구 단위로 연달아 받았다. 결론 · 근거 · 지문 · 매물 열(54)은 이 구간만 계산한다
  range=$(echo "SELECT coalesce(min(property_id), 0) || ' ' || coalesce(max(property_id), -1)
                  FROM loadtest.property_origin WHERE district = '$d';" | psql_ -At)
  read -r lo hi <<< "$range"
  log "== $d 결론 · 근거 · 지문 ($lo ~ $hi)"
  cat sql/51_guarantee_calc.inc.sql sql/54_judgement.sql | psql_ -v new_rows=true -v lo="$lo" -v hi="$hi"
  log "== $d 이력"
  psql_ -v district="$d" < sql/30_history.sql
  log "== $d 끝 ($((SECONDS - t0))s)"
  wait_standby
}

ranged() {   # ranged <이름> <LO> <HI> <STEP> <명령...> — 구간마다 명령(표준 입력 · -v lo/hi 는 명령이 받는다)을 돌린다
  local name=$1 from=$2 to=$3 step=$4; shift 4
  for ((a = from; a <= to; a += step)); do
    b=$(( a + step - 1 )); (( b > to )) && b=$to
    t0=$SECONDS
    "$@" "$a" "$b"
    log "$name $a ~ $b ($((SECONDS - t0))s)"
    disk_guard
    wait_standby
  done
}
ledger_fill_one()  { psql_ -v lo="$1" -v hi="$2" < sql/25_ledger_fill.sql; }
snapshot_base_one() { cat sql/51_guarantee_calc.inc.sql sql/54_judgement.sql | psql_ -v new_rows=false -v lo="$1" -v hi="$2"; }
notify_one()       { psql_ -v scope=range -v from_user="$1" -v to_user="$2" < sql/42_notification.sql; }

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
  plan)     psql_ -v target_total="${2:-1000000}" < sql/05_plan.sql ;;
  template) psql_ < sql/15_template.sql ;;
  drop-stage) # 매물 반영이 끝나면 적재용 표를 지운다(UNLOGGED · 원본 풀 — 1 GB 남짓). property_origin · baseline · criteria_fp 는 남긴다
    echo "DROP TABLE IF EXISTS loadtest.stage_property, loadtest.tmpl, loadtest.tmpl_g1, loadtest.tmpl_g2, loadtest.tmpl_g3,
            loadtest.tmpl_g4, loadtest.hub_ledger, loadtest.criteria_fp_seen;" | psql_ ;;
  fingerprint) psql_ < sql/53_fingerprint.sql ;;
  district) district "$2" "${3:-}" ;;
  all)
    shift
    # 옛 사용법(all <fake_limit> [구...]) — 첫 인자가 숫자면 가짜 상한으로 읽는다
    if [[ "${1:-}" =~ ^[0-9]+$ ]]; then FAKE_LIMIT=$1; shift; fi
    targets=("$@"); (( ${#targets[@]} )) || targets=("${DISTRICTS[@]}")
    for d in "${targets[@]}"; do district "$d" "${FAKE_LIMIT:-0}"; done ;;
  ledger-fill)       ranged ledger-fill "$2" "$3" "${4:-100000}" ledger_fill_one ;;
  snapshot-baseline) ranged snapshot-baseline "$2" "$3" "${4:-100000}" snapshot_base_one ;;
  copy-users)
    # 앞 N명만(USERS_TOTAL − 기준선 사용자). 생성기 파일은 user_id 순 — 앞 N줄 = user_id ≤ 1억 + N(40_users.sql 의 경계)
    n=$(echo "SELECT ${USERS_TOTAL:-300000} - count(*) FROM users WHERE user_id < 100000000;" | psql_ -At)
    log "copy-users 앞 ${n}명"
    # zcat | head 는 head 가 먼저 끝나면 zcat 이 SIGPIPE 로 죽는다 — pipefail 을 이 두 줄에서만 끈다
    set +o pipefail
    zcat "$2" | head -n "$n" | psql_ -c "\copy loadtest.stage_users FROM STDIN (FORMAT csv, NULL '')"
    zcat "$3" | head -n "$n" | psql_ -c "\copy loadtest.stage_user_auth FROM STDIN (FORMAT csv, NULL '')"
    set -o pipefail ;;
  users)    psql_ -v users_total="${USERS_TOTAL:-300000}" < sql/40_users.sql; wait_standby ;;
  pick)     psql_ < sql/41_pick.sql ;;
  test-wishlist) psql_ < sql/43_test_accounts.sql; wait_standby ;;
  notify)   # notify FROM TO [STEP] — 생성기 사용자 구간마다 커밋(기본 5만)
    ranged notify "$2" "$3" "${4:-50000}" notify_one ;;
  notify-test) psql_ -v scope=test < sql/42_notification.sql; wait_standby ;;
  vacuum)   for t in property risk_analysis building_registry building_ledger ownership_history mortgage_history users user_auth wishlist notification wishlist_notification notification_subscription; do
              t0=$SECONDS; echo "VACUUM (ANALYZE) $t;" | psql_; log "vacuum $t ($((SECONDS - t0))s)"; wait_standby
            done ;;
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
  guarantee-check)   # guarantee-check [new] [LO HI] — 51_guarantee_check.sql. new 를 주면 새 매물(52 앞뒤 확인)
    scope=false; if [[ "${2:-}" == new ]]; then scope=true; shift; fi
    # 새 매물 전체를 한 번에 돌리면 계산 결과(468만 행)가 임시 표로 수 GB 쓰인다 — 구간을 반드시 준다(guarantee-check-all)
    if [[ $scope == true && -z "${3:-}" ]]; then echo "새 매물은 구간(LO HI, 50만 이하)을 준다 — 전체는 guarantee-check-all" >&2; exit 2; fi
    cat sql/51_guarantee_calc.inc.sql sql/51_guarantee_check.sql       | psql_ -v new_rows="$scope" -v lo="${2:-0}" -v hi="${3:-9223372036854775807}" ;;
  guarantee-check-all)  # guarantee-check-all <LO> <HI> [STEP] — 새 매물을 구간(기본 50만)마다 대조. 구간마다 any_field 가 0 이어야 한다
    from=$2; to=$3; step=${4:-500000}
    for ((a = from; a <= to; a += step)); do
      b=$(( a + step - 1 )); (( b > to )) && b=$to
      t0=$SECONDS
      cat sql/51_guarantee_calc.inc.sql sql/51_guarantee_check.sql | psql_ -v new_rows=true -v lo="$a" -v hi="$b"
      log "guarantee-check-all $a ~ $b ($((SECONDS - t0))s)"
      disk_guard
    done ;;
  guarantee-recalc)  # guarantee-recalc <LO> <HI> [STEP] — 52_guarantee_recalc.sql 를 property_id 구간마다(기본 20만) 한 트랜잭션으로
    from=$2; to=$3; step=${4:-200000}
    for ((a = from; a <= to; a += step)); do
      b=$(( a + step - 1 )); (( b > to )) && b=$to
      t0=$SECONDS
      cat sql/51_guarantee_calc.inc.sql sql/52_guarantee_recalc.sql | psql_ -v new_rows=true -v lo="$a" -v hi="$b"
      log "guarantee-recalc $a ~ $b ($((SECONDS - t0))s)"
      disk_guard
      wait_standby
    done ;;
  *) sed -n '2,25p' "$0"; exit 1 ;;
esac
