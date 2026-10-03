#!/usr/bin/env bash
# auto_explain 켜고 끄기 — 문턱을 넘은 SQL 의 실행 계획을 DB 로그에 남긴다(INF-06 #378). **운영 작업 — 서버 상태를 바꾼다.**
#
#   bash auto-explain.sh on <문턱 ms> [--recreate-slots]    앱 DB 에 auto_explain 을 건다
#   bash auto-explain.sh off [--recreate-slots]             건 설정을 모두 지운다
#   bash auto-explain.sh status                             DB 단위 설정과 앱 세션의 가장 오래된 시작 시각
#
# 재기동 없이 건다 — 서버 설정(-c)을 바꾸면 DB 를 재기동해야 하고 그것이 회차 조건을 바꾼다. 대신 앱 DB 에
# ALTER DATABASE … SET session_preload_libraries = 'auto_explain' 을 두면 **새 세션부터** 모듈이 실린다
# (PG 17 문서 auto-explain.html — 관측 설계서 8장이 인용). session_preload_libraries 는 superuser 만 바꿀 수 있다 —
# 컨테이너 안 POSTGRES_USER(superuser, 운영 Compose 의 max_connections 주석)로 돈다.
#
# DB-01(primary)에서만 한다. ALTER DATABASE 는 카탈로그(pg_db_role_setting)에 쓰이므로 복제로 DB-02 에도 같게 걸린다 —
# 읽기 분산으로 DB-02 에 가는 조회도 계획이 DB-02 로그에 남는다(collect.sh 가 두 노드 다 읽는다).
#
# 거는 값(모두 ALTER DATABASE <앱 DB> SET)
#   session_preload_libraries        auto_explain
#   auto_explain.log_min_duration    <문턱>ms
#   auto_explain.log_analyze         on    — 실제 행수 · 루프. **문턱 아래 문장도 계측이 붙는다**(문서: 모든 문장에 계측 비용)
#   auto_explain.log_buffers         on    — 버퍼 적중 · 읽기
#   auto_explain.log_timing          off   — 노드별 시각 측정을 끈다. analyze 의 비용 대부분이 여기다(문서 권고)
#   auto_explain.log_nested_statements off
#   auto_explain.log_format          text
# 앱 DB 에 붙는 모든 역할에 걸린다 — 슬롯 풀 · 수집기(rental_monitor) · 백업. 수집기 질의도 문턱을 넘으면 남는다.
#
# **새 세션부터다.** 슬롯의 Hikari 풀은 커넥션을 max-lifetime(30분, application.yml)까지 들고 있으므로, 켜고 끈 뒤 바로
# 반영하려면 슬롯을 재생성해야 한다 — --recreate-slots 가 슬롯 넷을 하나씩 재생성하고 readiness 를 기다린다(부하 없는 때만).
# 그러지 않으면 30분을 기다린다. status 의 「앱 세션 가장 오래된 시작」이 설정 시각보다 뒤면 전부 새 세션이다.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() {
  echo "사용법: bash auto-explain.sh on <문턱 ms> [--recreate-slots] | off [--recreate-slots] | status" >&2
  exit 2
}
ACTION=${1:-}; shift || true
MIN_MS="" RECREATE=0
case $ACTION in
  on)
    MIN_MS=${1:-}; shift || true
    [[ $MIN_MS =~ ^[0-9]+$ ]] || usage ;;
  off|status) ;;
  *) usage ;;
esac
case ${1:-} in
  "") ;;
  --recreate-slots) [ "$ACTION" != status ] || usage; RECREATE=1 ;;
  *) usage ;;
esac

PRIMARY=db01

status() {
  psql_node "$PRIMARY" <<'SQL'
\pset format aligned
\pset tuples_only off
SELECT coalesce(d.datname, '(모든 DB)') AS db, coalesce(r.rolname, '(모든 역할)') AS role, s.setconfig
FROM pg_db_role_setting s
LEFT JOIN pg_database d ON d.oid = s.setdatabase
LEFT JOIN pg_roles r ON r.oid = s.setrole
ORDER BY 1, 2;
SELECT min(backend_start) AS "앱 DB 세션 가장 오래된 시작", count(*) AS "세션 수", now() AS "지금"
FROM pg_stat_activity
WHERE backend_type = 'client backend' AND datname = current_database() AND pid <> pg_backend_pid();
SQL
}

case $ACTION in
  on)
    log "[DB-01] auto_explain 켬 — 문턱 ${MIN_MS}ms"
    # LOAD 를 먼저 — 모듈이 이미지에 있는지 보고, 실린 세션에서는 auto_explain.* 값이 형식 검사를 받는다(오타가 그대로 저장되지 않게)
    psql_node "$PRIMARY" <<SQL
LOAD 'auto_explain';
ALTER DATABASE :"appdb" SET session_preload_libraries = 'auto_explain';
ALTER DATABASE :"appdb" SET auto_explain.log_min_duration = '${MIN_MS}ms';
ALTER DATABASE :"appdb" SET auto_explain.log_analyze = on;
ALTER DATABASE :"appdb" SET auto_explain.log_buffers = on;
ALTER DATABASE :"appdb" SET auto_explain.log_timing = off;
ALTER DATABASE :"appdb" SET auto_explain.log_nested_statements = off;
ALTER DATABASE :"appdb" SET auto_explain.log_format = 'text';
SQL
    ;;
  off)
    log "[DB-01] auto_explain 끔 — 건 설정을 지운다"
    psql_node "$PRIMARY" <<'SQL'
ALTER DATABASE :"appdb" RESET session_preload_libraries;
ALTER DATABASE :"appdb" RESET auto_explain.log_min_duration;
ALTER DATABASE :"appdb" RESET auto_explain.log_analyze;
ALTER DATABASE :"appdb" RESET auto_explain.log_buffers;
ALTER DATABASE :"appdb" RESET auto_explain.log_timing;
ALTER DATABASE :"appdb" RESET auto_explain.log_nested_statements;
ALTER DATABASE :"appdb" RESET auto_explain.log_format;
SQL
    ;;
esac

status
[ "$ACTION" = status ] && exit 0

if [ "$RECREATE" -eq 1 ]; then
  recreate_slots
  status
else
  log "새 세션부터 적용된다 — 슬롯 풀의 기존 커넥션은 max-lifetime(30분)까지 옛 설정이다."
  log "바로 반영하려면(부하 없는 때) bash auto-explain.sh $ACTION${MIN_MS:+ $MIN_MS} --recreate-slots"
fi
