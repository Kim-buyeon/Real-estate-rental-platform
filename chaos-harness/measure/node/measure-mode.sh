#!/usr/bin/env bash
# 측정 모드 켜고 끄기 — 앱 슬롯의 추적 설정을 측정용(agent-measure)으로 바꾸고 사본을 부하 생성기로 보낸다(INF-06 #378).
# **운영 작업 — 노드 .env 를 고치고 슬롯을 재생성한다.**
#
#   bash measure-mode.sh on <생성기 사설 IP> [--no-recreate]    두 줄을 넣고 슬롯 넷을 하나씩 재생성
#   bash measure-mode.sh off [--no-recreate]                    두 줄을 지우고 슬롯 넷을 하나씩 재생성
#   bash measure-mode.sh status                                 두 줄 · 에이전트 스위치 · 노드 체크아웃이 측정 모드를 아는지
#
# 앱 노드(APP-01 · APP-02)의 infra/.env 에
#   OTEL_AGENT_PROFILE=agent-measure                                    운영 Compose 가 infra/otel/agent-measure.yaml 을 슬롯에 붙인다
#   OTEL_MEASURE_RECEIVER_ENDPOINT=http://<생성기 사설 IP>:4318/v1/traces   그 설정의 두 번째 내보내기(trace-receiver.sh 의 수신기)
# 를 넣고(off 는 지운다 — 없으면 평시 agent.yaml), 마운트 · 환경이 바뀌었으므로 슬롯을 재생성해야 반영된다.
# 재생성은 부하가 없는 때(회차 사이)에만 한다 — upstream 에서 빼지 않는다(lib.sh 의 recreate_slots 주석).
#
# 에이전트 자체의 스위치는 OTEL_JAVAAGENT_ENABLED 다(운영 Compose x-app). 이 스크립트는 그 값을 바꾸지 않는다 —
# true 가 아니면 측정 모드를 켜도 추적이 나오지 않으므로 경고만 한다.
# .env 의 다른 줄은 노드 밖으로 나오지 않는다 — 노드에서 세 키만 grep 으로 거른다.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() { echo "사용법: bash measure-mode.sh on <생성기 사설 IP> [--no-recreate] | off [--no-recreate] | status" >&2; exit 2; }
ACTION=${1:-}; shift || true
GEN=${GENERATOR_IP:-}
case $ACTION in
  on)
    if [ -n "${1:-}" ] && [ "${1:-}" != --no-recreate ]; then GEN=$1; shift; fi
    [[ $GEN =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "생성기 사설 IP 가 필요하다(인자 또는 GENERATOR_IP)" >&2; usage; } ;;
  off|status) ;;
  *) usage ;;
esac
RECREATE=1
case ${1:-} in
  "") ;;
  --no-recreate) [ "$ACTION" != status ] || usage; RECREATE=0 ;;
  *) usage ;;
esac

KEYS_RE='OTEL_AGENT_PROFILE|OTEL_MEASURE_RECEIVER_ENDPOINT|OTEL_JAVAAGENT_ENABLED'

status_node() {
  local node=$1
  log "[$(node_label "$node")]"
  on_node "$node" "bash -s -- $(q "$NODE_DIR") $(q "$KEYS_RE")" <<'EOF' | sed 's|^|    |'
set -u
cd "$1" || { echo "체크아웃이 없다: $1"; exit 1; }
lines=$(grep -E "^($2)=" .env 2>/dev/null)
echo "${lines:-(.env 에 세 키 없음)}"
case $(grep -E '^OTEL_JAVAAGENT_ENABLED=' .env | tail -1 | cut -d= -f2- | tr -d "\"' ") in
  true) echo "에이전트 켜짐(OTEL_JAVAAGENT_ENABLED=true)" ;;
  *)    echo "경고: 에이전트 꺼짐 — OTEL_JAVAAGENT_ENABLED 가 true 가 아니다. 측정 모드를 켜도 추적이 나오지 않는다" ;;
esac
if grep -q 'OTEL_AGENT_PROFILE' docker-compose.yml && [ -f otel/agent-measure.yaml ]; then
  echo "체크아웃 — 측정 모드를 안다(docker-compose.yml · otel/agent-measure.yaml)"
else
  echo "경고: 이 노드의 체크아웃이 측정 모드 이전이다 — 먼저 체크아웃을 맞춘다(운영 절차서)"
fi
# 도는 슬롯이 실제로 붙인 설정 파일 — 마운트 원본 이름으로 본다
for s in app-1 app-2; do
  id=$(docker compose ps -q "$s" 2>/dev/null)
  [ -n "$id" ] || { echo "$s 없음"; continue; }
  src=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/etc/rental/otel/agent.yaml"}}{{.Source}}{{end}}{{end}}' "$id")
  echo "$s 가 붙인 설정 — ${src##*/}"
done
EOF
}

set_node() {  # <노드> on|off <주소>
  local node=$1 mode=$2 endpoint=${3:-}
  log "[$(node_label "$node")] .env — 측정 모드 $mode"
  # 값에는 | · 따옴표가 없다(고정 이름과 http://IP:4318/v1/traces) — sed 구분자를 | 로 쓴다(deploy.sh 의 set_env 와 같은 방식)
  on_node "$node" "bash -s -- $(q "$NODE_DIR") $mode $(q "$endpoint")" <<'EOF'
set -eu
cd "$1"
test -f .env
set_env() { if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi; }
if [ "$2" = on ]; then
  grep -q 'OTEL_AGENT_PROFILE' docker-compose.yml && [ -f otel/agent-measure.yaml ] \
    || { echo "체크아웃이 측정 모드 이전이다 — .env 를 바꾸지 않았다" >&2; exit 1; }
  # 기동 따라잡기 배치가 켜져 있으면 슬롯을 재생성할 때마다 배치가 돌고, 측정 모드는 그 구간을 남긴다 —
  # 로컬 실측(#378)에서 기동 한 번이 구간 약 26만 개 · 수신기 파일 1 GB 를 냈다(Grafana 예산도 같이 먹는다)
  [ "$(grep -E '^BATCH_STARTUPCATCHUP_ENABLED=' .env | tail -1 | cut -d= -f2- | tr -d "\"' ")" = false ] \
    || { echo "BATCH_STARTUPCATCHUP_ENABLED=false 가 아니다 — 먼저 끈다(.env 를 바꾸지 않았다)" >&2; exit 1; }
  set_env OTEL_AGENT_PROFILE agent-measure
  set_env OTEL_MEASURE_RECEIVER_ENDPOINT "$3"
else
  sed -i -e '/^OTEL_AGENT_PROFILE=/d' -e '/^OTEL_MEASURE_RECEIVER_ENDPOINT=/d' .env
fi
EOF
}

if [ "$ACTION" = status ]; then
  for node in $APP_NODES; do status_node "$node"; done
  exit 0
fi

ENDPOINT=""
if [ "$ACTION" = on ]; then ENDPOINT="http://$GEN:4318/v1/traces"; fi
for node in $APP_NODES; do
  set_node "$node" "$ACTION" "$ENDPOINT" || die "[$(node_label "$node")] .env 를 고치지 못했다 — 앞 노드는 이미 바뀌었다. status 로 확인한다"
done

if [ "$RECREATE" -eq 1 ]; then
  recreate_slots
else
  log "재생성 건너뜀(--no-recreate) — 슬롯을 재생성해야 반영된다"
fi
for node in $APP_NODES; do status_node "$node"; done
if [ "$ACTION" = on ]; then
  log "생성기 수신기가 떠 있어야 한다 — trace-receiver.sh status. 앱 노드 → $GEN:4318 은 보안 그룹이 열어야 한다"
fi
