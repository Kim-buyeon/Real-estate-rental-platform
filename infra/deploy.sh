#!/usr/bin/env bash
# 슬롯 순차 교체 배포 — 운영 절차서 3장. infra/ 에서 실행한다.
#
#   bash deploy.sh <태그>              일상 배포. 첫 슬롯 복귀 뒤 관찰(smoke.sh)한다
#   bash deploy.sh <태그> --rollback   롤백. 되돌아갈 태그는 이미 검증된 버전이라 관찰을 건너뛴다(3.4)
#
# 태그는 커밋 해시다(image.yml). 순서: 드리프트 가드 → 원격 노드 확인 → 이미지 확인 → (슬롯마다) 제외 → drain → 교체 →
# readiness → 복귀. 실패하면 그 슬롯을 down 으로 두고 멈춘다 — 남은 슬롯이 구버전으로 계속 서비스한다(3.1).
# 착수 단계에서 upstream 설정이 추적 상태와 같은지 본다. SKIP_DRIFT_CHECK=1 로 끌 수 있다(기본 켜짐).
#
# 슬롯은 넷이다(INF-01) — 이 노드(APP-01)의 app-1 · app-2 와 앱 노드 APP-02 의 app-1 · app-2. 앞단 nginx 는 이 노드에만 있으므로
# upstream 제외 · 복귀 · readiness · 관찰은 전부 여기서 하고, APP-02 슬롯의 교체만 SSH 로 그 노드의 체크아웃에서 docker compose 를 부른다.
#   APP_NODE_SSH   APP-02 에 붙는 SSH 대상(기본 deploy@10.20.20.30). **빈 값으로 두면(APP_NODE_SSH=) 원격 슬롯을 건너뛴다** —
#                  APP-02 가 없거나 꺼진 구성 호환. 원격 슬롯이 readiness 에 응답하면(떠 있으면) 아무것도 바꾸지 않고 멈춘다
#   APP_NODE_DIR   APP-02 의 체크아웃 안 infra/(기본 /home/deploy/rental/infra — 노드별 체크아웃 자리, 운영 절차서 9장)
# SSH 는 비대화식(BatchMode)이다 — 이 노드의 deploy 계정이 APP-02 의 deploy 에 키로 붙을 수 있어야 한다. 비밀번호 · 호스트 키
# 확인 질문에서 멈추지 않고 실패한다.
set -euo pipefail
cd "$(dirname "$0")"

TAG=${1:?"사용법: bash deploy.sh <태그> [--rollback]"}
MODE=${2:-}
REGISTRY=${REGISTRY:-ghcr.io/kim-buyeon/real-estate-rental-platform}
APP="$REGISTRY/backend:$TAG"
WEB="$REGISTRY/frontend:$TAG"
UP=./nginx/conf.d/upstream.conf          # 컨테이너에 마운트된 호스트 파일
# APP-02 사설 IP — upstream.conf 의 원격 두 줄과 같은 값이어야 한다(그 파일 머리 주석)
REMOTE_ADDR=10.20.20.30
# :- 가 아니라 - 다 — 빈 값을 「건너뛴다」로 읽어야 하므로 기본값은 변수가 아예 없을 때만 쓴다
APP_NODE_SSH=${APP_NODE_SSH-deploy@$REMOTE_ADDR}
APP_NODE_DIR=${APP_NODE_DIR:-/home/deploy/rental/infra}
# 「이름 주소 포트 위치」. 교체 순서 — 노드마다 한 슬롯씩 번갈아 바꾼다. 어느 순간에도 두 노드 모두에 서비스하는 슬롯이
# 하나씩 남아, 교체 도중 한 노드가 통째로 멈춰도 다른 노드가 받는다. 첫 슬롯(관찰 대상)은 APP-02 의 app-2 다
SLOTS=(
  "app-2 $REMOTE_ADDR 8082 remote"
  "app-2 127.0.0.1 8082 local"
  "app-1 $REMOTE_ADDR 8081 remote"
  "app-1 127.0.0.1 8081 local"
)
REPO_ROOT=""                             # 드리프트 가드가 채운다. 비어 있으면 가드를 건너뛴 것이다
UP_REL=""                                # 저장소 루트 기준의 UP 경로
DRAIN=${DRAIN:-30}                       # 제외 뒤 진행 중인 요청이 끝나기를 기다리는 초 — graceful 30초와 같다
WARMUP=${WARMUP:-60}                     # 첫 슬롯 복귀 뒤 JIT 워밍업 — 3.3

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

reload() { docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload; }
# sed · grep 정규식에 넣을 주소 — 점을 글자 그대로
addr_re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }
# down 플래그만 토글한다. 서버 목록 자체는 바꾸지 않는다(3.1). 인자는 주소 · 포트 — 두 노드의 슬롯이 같은 포트를 쓴다.
# 「server 주소:포트」로 찾는다 — 머리 주석에 적힌 주소에 걸리지 않게
# 이미 down 인 줄은 건드리지 않는다 — 실패로 멈춘 슬롯을 롤백할 때 "down down" 이 되지 않게
down_slot() {
  local a; a=$(addr_re "$1")
  sed -i "/server ${a}:$2 .* down;/!s|\(server ${a}:$2[^;]*\);|\1 down;|" "$UP"; reload
}
up_slot() {
  local a; a=$(addr_re "$1")
  sed -i "s|\(server ${a}:$2[^;]*\) down;|\1;|" "$UP"; reload
}

# APP-02 에서 명령을 돈다. 인자가 원격 셸의 한 줄이 된다 — 값은 부르는 쪽이 printf %q 로 감싼다
# ServerAlive — 연결이 선 뒤 사설망에서 패킷이 버려지면 ssh 가 끝없이 매달린다. 15초 × 4 = 약 60초 무응답이면 끊고 실패한다
remote() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$APP_NODE_SSH" "$@"
}

# 로컬에 없을 때만 받는다 — 로컬 빌드 이미지로 시험할 수 있게
ensure_image() { docker image inspect "$1" > /dev/null 2>&1 || docker pull "$1"; }
# APP-02 에서 같은 판정 — 그 노드는 APP-01(NAT)을 거쳐 레지스트리에서 받는다
ensure_image_remote() {
  local q; q=$(printf %q "$1")
  remote "docker image inspect $q > /dev/null 2>&1 || docker pull $q"
}

# ── 설정 드리프트 가드 ──────────────────────────────────────────────────────────────────────
# 이 스크립트는 upstream.conf 의 down 플래그를 sed 로 토글한다. 배포가 실패로 멈추면 그 슬롯은 down 인 채 남는데,
# 운영 절차서 3.1 은 Nginx 설정 변경을 "체크아웃 → nginx -t → reload" 로 반영하라고 정한다. 그 체크아웃이 down 을
# 지워 **죽은 슬롯을 upstream 에 되살린다.** 그래서 착수 단계에서 파일이 추적 상태와 같은지 한 번 본다.
# 루프 안이나 종료 시점에는 같은 판정을 하지 않는다 — 스크립트 자신이 파일을 고치므로 배포 도중에는 항상 다르다.

# down 으로 남은 슬롯을 노드 · 이름으로 짚어 준다
report_down_slots() {
  local slot name addr port where found=0
  for slot in "${SLOTS[@]}"; do
    read -r name addr port where <<< "$slot"
    if grep -q "server $(addr_re "$addr"):${port}[^;]* down;" "$UP"; then
      log "    down 으로 남은 슬롯: $name ($where · $addr:$port)"
      found=1
    fi
  done
  [ "$found" -eq 1 ] || log "    down 플래그는 남아 있지 않다. 다른 항목이 바뀌었다 — 아래 차이를 본다"
  return 0
}

# 루프 시작 전 한 번만 부른다. 어긋났으면 중단한다
check_upstream_drift() {
  # 저장소 위치를 먼저 푼다 — 건너뛰는 경우에도 종료 시점 확인(warn_if_drift_left)은 할 수 있어야 한다
  if ! command -v git > /dev/null 2>&1; then
    log "드리프트 가드 건너뜀 — git 이 없다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi
  if ! REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null); then
    REPO_ROOT=""
    log "드리프트 가드 건너뜀 — 저장소 밖에서 실행됐다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi
  UP_REL="${PWD#"$REPO_ROOT"/}/nginx/conf.d/upstream.conf"

  # 롤백은 이 가드가 잡아내는 상태 — 배포가 실패해 슬롯이 down 으로 남은 상태 — 를 푸는 수단이다.
  # 여기서 막으면 운영 절차서 3.1 이 정한 복구 경로가 자기 자신에게 차단된다. 롤백은 그대로 진행하고,
  # 끝난 뒤 warn_if_drift_left 가 down 이 다 풀렸는지 본다.
  if [ "$MODE" = "--rollback" ]; then
    log "드리프트 가드 건너뜀 — 롤백이다. 실패로 남은 down 슬롯을 되살리는 것이 이 실행의 목적이다"
    return 0
  fi
  if [ "${SKIP_DRIFT_CHECK:-0}" = "1" ]; then
    log "드리프트 가드 건너뜀 — SKIP_DRIFT_CHECK=1 로 껐다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi

  local drift
  if ! drift=$(git -C "$REPO_ROOT" status --porcelain -- "$UP_REL" 2>/dev/null); then
    REPO_ROOT=""
    log "드리프트 가드 건너뜀 — git status 를 실행하지 못했다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi
  [ -n "$drift" ] || return 0

  log "!!! upstream 설정이 추적 상태와 다르다. 배포를 중단한다 — 운영 절차서 3.1"
  log "    대상: $UP_REL"
  report_down_slots
  git -C "$REPO_ROOT" --no-pager diff -- "$UP_REL" | sed 's|^|    |'
  log "    복구: 먼저 down 슬롯을 되살린다 — bash deploy.sh <직전 태그> --rollback (그 뒤 파일이 추적 상태로 돌아온다)."
  log "          되살릴 수 없으면 원인을 확인한 뒤 git -C $REPO_ROOT checkout -- $UP_REL 로 되돌리고"
  log "          docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload 한다(운영 절차서 3.1)."
  log "          절차서에 없는 상황이라 사람이 판단해 넘어가려면 SKIP_DRIFT_CHECK=1 로 이 가드를 끈다"
  exit 1
}

# 배포가 끝난 뒤의 확인. 실패로 만들지 않는다 — 여기까지 왔으면 서비스는 이미 복구된 상태다
warn_if_drift_left() {
  [ -n "$REPO_ROOT" ] || return 0        # 가드를 건너뛴 환경이면 확인할 수단도 없다
  local drift
  drift=$(git -C "$REPO_ROOT" status --porcelain -- "$UP_REL" 2>/dev/null) || return 0
  [ -n "$drift" ] || return 0
  log "경고: 배포는 끝났는데 upstream 설정이 추적 상태와 다르다. down 플래그가 모두 풀렸어야 한다"
  report_down_slots
  log "       다음 배포는 착수 단계에서 이 차이 때문에 멈춘다. 지금 확인해 되돌린다(운영 절차서 3.1)"
  return 0
}

check_upstream_drift

# ── 원격 노드(APP-02) 확인 ─────────────────────────────────────────────────────────────────
# 슬롯을 하나라도 건드리기 전에 본다 — 도중에 SSH 가 안 되는 것을 알면 한 슬롯이 이미 down 인 채 멈춘다.
if [ -z "$APP_NODE_SSH" ]; then
  log "원격 슬롯 건너뜀 — APP_NODE_SSH 가 비어 있다(APP-02 가 없거나 꺼진 구성)"
  # 건너뛰어도 되는 것은 원격 슬롯이 요청을 받지 않을 때뿐이다. 하나라도 살아 있으면 그 슬롯은 구버전으로 남고,
  # 배포 끝의 화면 교체가 신버전 화면에서 그 구버전 API 를 부르게 만든다(운영 절차서 3.1 — 화면은 슬롯이 모두 끝난 뒤).
  # 그래서 readiness 를 짧게 한 번 두드려 UP 이면 아무것도 바꾸지 않고 멈춘다. 응답이 없으면(APP-02 꺼짐) 그대로 진행한다
  REMOTE_UP=0
  for slot in "${SLOTS[@]}"; do
    read -r name addr port where <<< "$slot"
    [ "$where" = remote ] || continue
    if curl -fs --connect-timeout 2 -m 3 "http://$addr:$port/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then
      log "!!! 원격 슬롯 $name ($addr:$port) 이 떠 있다(readiness UP). 건너뛰면 구버전으로 남는다"
      REMOTE_UP=1
    else
      log "    원격 슬롯 $name ($addr:$port) 응답 없음 — 건너뛴다"
    fi
  done
  if [ "$REMOTE_UP" -eq 1 ]; then
    log "!!! 아무것도 바꾸지 않고 중단한다. APP-02 가 떠 있으면 APP_NODE_SSH 를 비우지 않고(기본값) 다시 배포한다"
    exit 1
  fi
else
  log "원격 노드 확인 — $APP_NODE_SSH:$APP_NODE_DIR"
  if ! remote "cd $(printf %q "$APP_NODE_DIR") && test -f docker-compose.yml && test -f .env"; then
    log "!!! APP-02 에 닿지 못했거나 체크아웃 · .env 가 없다. 아무 슬롯도 건드리지 않고 중단한다"
    log "    APP-02 없이 배포하려면 APP_NODE_SSH= (빈 값)으로 다시 실행한다 — 그때 나오는 경고를 함께 본다"
    exit 1
  fi
  # 두 노드의 Compose 정의가 다르면 같은 태그라도 슬롯 설정이 다르게 뜬다. 멈추지는 않는다 — 체크아웃을 맞추는 것은 운영 절차다
  LOCAL_REV=$(git rev-parse HEAD 2>/dev/null || echo "?")
  REMOTE_REV=$(remote "git -C $(printf %q "$APP_NODE_DIR") rev-parse HEAD" 2>/dev/null || echo "?")
  if [ "$LOCAL_REV" != "$REMOTE_REV" ]; then
    log "경고: 두 노드의 체크아웃이 다르다 — APP-01 $LOCAL_REV · APP-02 $REMOTE_REV. 슬롯 정의(docker-compose.yml)가 어긋날 수 있다"
  fi
fi

# 배포가 끝난 뒤에만 .env 에 적는다. 중간에 멈추면 .env 는 구버전을 가리키므로, 이후 docker compose up 이 남은 슬롯을
# 신버전으로 바꾸지 않는다. APP-02 의 .env 도 같은 시점에 적는다(아래).
set_env() {
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}
# 값은 이미지 참조(레지스트리/이름:태그)라 sed 구분자 | · 따옴표가 들어가지 않는다
set_env_remote() {
  remote "cd $(printf %q "$APP_NODE_DIR") && if grep -q '^$1=' .env; then sed -i 's|^$1=.*|$1=$2|' .env; else echo '$1=$2' >> .env; fi"
}

log "이미지 확인 — $TAG"
ensure_image "$APP"
ensure_image "$WEB"
if [ -n "$APP_NODE_SSH" ]; then
  log "이미지 확인(APP-02) — $TAG"
  if ! ensure_image_remote "$APP"; then
    log "!!! APP-02 에서 이미지를 확인 · 수신하지 못했다. 아무것도 바꾸지 않고 배포를 중단한다"
    exit 1
  fi
fi

FIRST=1
for slot in "${SLOTS[@]}"; do
  read -r NAME ADDR PORT WHERE <<< "$slot"
  ID="$NAME@$ADDR"                       # 로그의 슬롯 이름 — 두 노드의 슬롯 이름이 같다

  if [ "$WHERE" = remote ] && [ -z "$APP_NODE_SSH" ]; then
    log "[$ID] 건너뜀 — APP_NODE_SSH 가 비어 있다"
    continue
  fi

  log "[$ID] upstream 제외 후 drain ${DRAIN}초"
  down_slot "$ADDR" "$PORT"
  sleep "$DRAIN"

  log "[$ID] 이미지 교체"
  if [ "$WHERE" = remote ]; then
    if ! remote "cd $(printf %q "$APP_NODE_DIR") && APP_IMAGE=$(printf %q "$APP") docker compose up -d --no-deps --force-recreate $NAME"; then
      log "!!! [$ID] APP-02 에서 교체하지 못했다(SSH · docker compose 실패). down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
  else
    APP_IMAGE="$APP" docker compose up -d --no-deps --force-recreate "$NAME"
  fi

  # 원격 슬롯도 이 노드에서 본다 — nginx 가 요청을 보내는 길(사설망)과 같은 길이다
  log "[$ID] readiness 대기"
  for i in $(seq 1 60); do
    # 시간 상한 — 사설망에서 패킷이 버려지면 상한 없는 curl 은 회당 수십 초 매달려 「최대 120초」(60회 × 2초)가 깨진다
    if curl -fs --connect-timeout 2 -m 3 "http://$ADDR:$PORT/actuator/health/readiness" | grep -q '"status":"UP"'; then
      break
    fi
    if [ "$i" -eq 60 ]; then
      log "!!! [$ID] 기동 실패. down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
    sleep 2
  done

  # 첫 슬롯은 upstream 에 넣기 전에 직접 두드려 본다 — 신버전이 사용자 요청을 받기 전에 걸러진다.
  # 원격 슬롯을 건너뛴 경우에는 처음 실제로 바꾼 슬롯이 첫 슬롯이다
  if [ "$FIRST" -eq 1 ] && [ "$MODE" != "--rollback" ]; then
    log "[$ID] 워밍업 ${WARMUP}초 후 관찰"
    sleep "$WARMUP"
    if ! bash smoke.sh "$ADDR" "$PORT"; then
      log "!!! [$ID] 관찰 실패. down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
  fi
  FIRST=0

  log "[$ID] upstream 복귀"
  up_slot "$ADDR" "$PORT"
done

# 화면은 슬롯이 모두 신버전이 된 뒤에 바꾼다 — 중간에 멈추면 신버전 화면이 구버전 API 를 부르게 된다.
# 슬롯이 하나라 교체 순간 짧게 끊길 수 있다. 상태가 없어 다시 요청하면 된다.
log "[web] 교체"
WEB_IMAGE="$WEB" docker compose up -d --no-deps web

set_env APP_IMAGE "$APP"
set_env WEB_IMAGE "$WEB"
# APP-02 에는 화면이 없어 앱 이미지만 적는다.
# 여기서 실패하면 슬롯은 이미 모두 신버전인데 APP-02 의 .env 만 옛 태그다 — 그 노드에서 docker compose up 을 다시 부르면 슬롯이
# 구버전으로 돌아간다. 멈추지 않고 알린 뒤 정리 · 드리프트 확인까지 마치고 비0 으로 끝낸다
ENV_REMOTE_FAILED=0
if [ -n "$APP_NODE_SSH" ]; then
  if ! set_env_remote APP_IMAGE "$APP"; then
    ENV_REMOTE_FAILED=1
    log "!!! APP-02 의 .env 에 APP_IMAGE 를 적지 못했다. 슬롯은 모두 신버전이지만 그 노드의 .env 는 옛 태그를 가리킨다"
    log "    복구(APP-01 에서): ssh $APP_NODE_SSH \"cd $APP_NODE_DIR && sed -i 's|^APP_IMAGE=.*|APP_IMAGE=$APP|' .env && grep ^APP_IMAGE= .env\""
    log "    (.env 에 APP_IMAGE 줄이 없으면 echo 'APP_IMAGE=$APP' >> .env). 고치기 전에는 APP-02 에서 docker compose up 을 부르지 않는다"
  fi
fi

# 최근 태그 3개(지금 것 포함)를 남기고 지운다(3.4). 실행 중인 이미지는 docker 가 지우지 않는다
for repo in "$REGISTRY/backend" "$REGISTRY/frontend"; do
  docker images "$repo" --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -d'|' -f2 \
    | xargs -r docker rmi > /dev/null 2>&1 || true
done
# APP-02 도 같은 규칙이다(앱 이미지만 있다). 정리 실패는 배포 실패로 만들지 않는다
if [ -n "$APP_NODE_SSH" ]; then
  remote "docker images $(printf %q "$REGISTRY/backend") --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -d'|' -f2 | xargs -r docker rmi > /dev/null 2>&1" || true
fi

warn_if_drift_left

if [ "$ENV_REMOTE_FAILED" -eq 1 ]; then
  log "!!! 배포는 끝났으나 APP-02 .env 기록이 실패했다 — 위 복구 명령을 실행한다"
  exit 1
fi

log "배포 완료 — $TAG"
