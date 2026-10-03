#!/usr/bin/env bash
# 추적 수신기 — 부하 생성기에서 측정 모드 슬롯이 보내는 추적 사본을 파일로 받는다(INF-06 #378).
#
#   BIND_ADDR=<생성기 사설 IP> bash trace-receiver.sh start     컨테이너를 띄운다(이미 돌면 그대로)
#   bash trace-receiver.sh stop                                컨테이너를 지운다(받은 파일은 남는다)
#   bash trace-receiver.sh status
#
# OpenTelemetry Collector contrib 이미지에 otelcol.yaml(같은 폴더)을 붙여 OTLP/HTTP 4318 을 받고 $TRACE_DIR/spans.jsonl 에 쓴다.
# 회차마다 collect.sh 가 그 파일을 results/<회차>/traces/ 로 옮기고 비운다.
#
#   BIND_ADDR    4318 을 게시할 주소(기본 127.0.0.1 — 로컬 검증). 실제 회차는 생성기 사설 IP 하나다 — 앱 노드가 사설망으로 보낸다.
#                0.0.0.0 으로 열지 않는다 — Docker 게시 포트는 호스트 방화벽을 우회한다. 앱 노드에서 생성기 4318 로 가는 길은
#                보안 그룹이 열어야 한다(이 스크립트는 열지 않는다 — 콘솔 · CLI 조작은 운영 작업)
#   TRACE_DIR    받은 파일 자리(기본 chaos-harness/measure/trace-data — 저장소가 무시한다)
# 컨테이너는 실행한 사람의 uid:gid 로 돈다 — 받은 파일을 같은 사람이 옮기고 비울 수 있게.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

# 이미지 고정 — Docker Hub otel/opentelemetry-collector-contrib 의 0.161.0(2026-09-16 릴리스. 0.162.0 은 확인 시점에 이미지가 없었다)
IMAGE=otel/opentelemetry-collector-contrib:0.161.0
NAME=rental-trace-receiver
BIND_ADDR=${BIND_ADDR:-127.0.0.1}
TRACE_DIR=${TRACE_DIR:-$MEASURE_ROOT/trace-data}

usage() { echo "사용법: [BIND_ADDR=<생성기 사설 IP>] bash trace-receiver.sh start|stop|status" >&2; exit 2; }

# Git Bash(로컬 검증)에서는 docker 에 Windows 경로를 넘기고 MSYS 경로 변환을 끈다
host_path() { if pwd -W > /dev/null 2>&1; then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi; }

case ${1:-} in
  start)
    [ "$BIND_ADDR" != 0.0.0.0 ] || die "BIND_ADDR=0.0.0.0 은 쓰지 않는다 — 생성기 사설 IP 하나를 준다"
    if [ -n "$(docker ps -q -f "name=^${NAME}$")" ]; then
      log "이미 돈다 — $NAME"; docker ps -f "name=^${NAME}$"; exit 0
    fi
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    mkdir -p "$TRACE_DIR"
    DATA=$(host_path "$TRACE_DIR")
    CONF_DIR=$(host_path "$NODE_SCRIPTS")
    MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" --restart unless-stopped \
      --memory 256m --user "$(id -u):$(id -g)" \
      -p "$BIND_ADDR:4318:4318" \
      -v "$CONF_DIR/otelcol.yaml:/etc/otelcol-contrib/config.yaml:ro" \
      -v "$DATA:/data" \
      "$IMAGE" --config /etc/otelcol-contrib/config.yaml > /dev/null
    sleep 3
    if [ -z "$(docker ps -q -f "name=^${NAME}$")" ]; then
      docker logs "$NAME" 2>&1 | tail -20
      die "수신기가 뜨지 않았다 — 위 로그"
    fi
    log "수신기 기동 — http://$BIND_ADDR:4318/v1/traces → $TRACE_DIR/spans.jsonl"
    log "앱 노드의 측정 모드 주소: bash measure-mode.sh on $BIND_ADDR"
    ;;
  stop)
    if docker rm -f "$NAME" > /dev/null 2>&1; then log "수신기 지움 — 받은 파일은 $TRACE_DIR 에 남는다"; else log "수신기가 없다"; fi
    ;;
  status)
    docker ps -a -f "name=^${NAME}$"
    if [ -f "$TRACE_DIR/spans.jsonl" ]; then log "받은 파일 $(fsize "$TRACE_DIR/spans.jsonl") B — $TRACE_DIR/spans.jsonl"; fi
    ;;
  *) usage ;;
esac
