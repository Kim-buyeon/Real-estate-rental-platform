#!/usr/bin/env bash
# 슬롯 순차 교체 배포 — 운영 절차서 3장. infra/ 에서 실행한다.
#
#   bash deploy.sh <태그>              일상 배포. 첫 슬롯 복귀 뒤 관찰(smoke.sh)한다
#   bash deploy.sh <태그> --rollback   롤백. 되돌아갈 태그는 이미 검증된 버전이라 관찰을 건너뛴다(3.4)
#
# 태그는 커밋 해시다(image.yml). 순서: 드리프트 가드 → 이미지 확인 → (슬롯마다) 제외 → drain → 교체 → readiness → 복귀.
# 실패하면 그 슬롯을 down 으로 두고 멈춘다 — 남은 슬롯이 구버전으로 계속 서비스한다(3.1).
# 착수 단계에서 upstream 설정이 추적 상태와 같은지 본다. SKIP_DRIFT_CHECK=1 로 끌 수 있다(기본 켜짐).
set -euo pipefail
cd "$(dirname "$0")"

TAG=${1:?"사용법: bash deploy.sh <태그> [--rollback]"}
MODE=${2:-}
REGISTRY=${REGISTRY:-ghcr.io/kim-buyeon/real-estate-rental-platform}
APP="$REGISTRY/backend:$TAG"
WEB="$REGISTRY/frontend:$TAG"
UP=./nginx/conf.d/upstream.conf          # 컨테이너에 마운트된 호스트 파일
SLOTS=("app-2 8082" "app-1 8081")
REPO_ROOT=""                             # 드리프트 가드가 채운다. 비어 있으면 가드를 건너뛴 것이다
UP_REL=""                                # 저장소 루트 기준의 UP 경로
DRAIN=${DRAIN:-30}                       # 제외 뒤 진행 중인 요청이 끝나기를 기다리는 초 — graceful 30초와 같다
WARMUP=${WARMUP:-60}                     # 첫 슬롯 복귀 뒤 JIT 워밍업 — 3.3

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

reload() { docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload; }
# down 플래그만 토글한다. 서버 목록 자체는 바꾸지 않는다(3.1)
# 이미 down 인 줄은 건드리지 않는다 — 실패로 멈춘 슬롯을 롤백할 때 "down down" 이 되지 않게
down_slot() { sed -i "/127\.0\.0\.1:$1 .* down;/!s|\(127\.0\.0\.1:$1[^;]*\);|\1 down;|" "$UP"; reload; }
up_slot()   { sed -i "s|\(127\.0\.0\.1:$1[^;]*\) down;|\1;|" "$UP"; reload; }

# 로컬에 없을 때만 받는다 — 로컬 빌드 이미지로 시험할 수 있게
ensure_image() { docker image inspect "$1" > /dev/null 2>&1 || docker pull "$1"; }

# ── 설정 드리프트 가드 ──────────────────────────────────────────────────────────────────────
# 이 스크립트는 upstream.conf 의 down 플래그를 sed 로 토글한다. 배포가 실패로 멈추면 그 슬롯은 down 인 채 남는데,
# 운영 절차서 3.1 은 Nginx 설정 변경을 "체크아웃 → nginx -t → reload" 로 반영하라고 정한다. 그 체크아웃이 down 을
# 지워 **죽은 슬롯을 upstream 에 되살린다.** 그래서 착수 단계에서 파일이 추적 상태와 같은지 한 번 본다.
# 루프 안이나 종료 시점에는 같은 판정을 하지 않는다 — 스크립트 자신이 파일을 고치므로 배포 도중에는 항상 다르다.

# down 으로 남은 슬롯을 이름으로 짚어 준다
report_down_slots() {
  local slot name port found=0
  for slot in "${SLOTS[@]}"; do
    read -r name port <<< "$slot"
    if grep -q "127\.0\.0\.1:${port}[^;]* down;" "$UP"; then
      log "    down 으로 남은 슬롯: $name (127.0.0.1:$port)"
      found=1
    fi
  done
  [ "$found" -eq 1 ] || log "    down 플래그는 남아 있지 않다. 다른 항목이 바뀌었다 — 아래 차이를 본다"
  return 0
}

# 루프 시작 전 한 번만 부른다. 어긋났으면 중단한다
check_upstream_drift() {
  if [ "${SKIP_DRIFT_CHECK:-0}" = "1" ]; then
    log "드리프트 가드 건너뜀 — SKIP_DRIFT_CHECK=1 로 껐다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi
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

# 배포가 끝난 뒤에만 .env 에 적는다. 중간에 멈추면 .env 는 구버전을 가리키므로, 이후 docker compose up 이 남은 슬롯을
# 신버전으로 바꾸지 않는다.
set_env() {
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}

log "이미지 확인 — $TAG"
ensure_image "$APP"
ensure_image "$WEB"

FIRST=1
for slot in "${SLOTS[@]}"; do
  read -r NAME PORT <<< "$slot"

  log "[$NAME] upstream 제외 후 drain ${DRAIN}초"
  down_slot "$PORT"
  sleep "$DRAIN"

  log "[$NAME] 이미지 교체"
  APP_IMAGE="$APP" docker compose up -d --no-deps --force-recreate "$NAME"

  log "[$NAME] readiness 대기"
  for i in $(seq 1 60); do
    if curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" | grep -q '"status":"UP"'; then
      break
    fi
    if [ "$i" -eq 60 ]; then
      log "!!! [$NAME] 기동 실패. down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
    sleep 2
  done

  # 첫 슬롯은 upstream 에 넣기 전에 직접 두드려 본다 — 신버전이 사용자 요청을 받기 전에 걸러진다
  if [ "$FIRST" -eq 1 ] && [ "$MODE" != "--rollback" ]; then
    log "[$NAME] 워밍업 ${WARMUP}초 후 관찰"
    sleep "$WARMUP"
    if ! bash smoke.sh "$PORT"; then
      log "!!! [$NAME] 관찰 실패. down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
  fi
  FIRST=0

  log "[$NAME] upstream 복귀"
  up_slot "$PORT"
done

# 화면은 두 슬롯이 모두 신버전이 된 뒤에 바꾼다 — 중간에 멈추면 신버전 화면이 구버전 API 를 부르게 된다.
# 슬롯이 하나라 교체 순간 짧게 끊길 수 있다. 상태가 없어 다시 요청하면 된다.
log "[web] 교체"
WEB_IMAGE="$WEB" docker compose up -d --no-deps web

set_env APP_IMAGE "$APP"
set_env WEB_IMAGE "$WEB"

# 최근 태그 3개(지금 것 포함)를 남기고 지운다(3.4). 실행 중인 이미지는 docker 가 지우지 않는다
for repo in "$REGISTRY/backend" "$REGISTRY/frontend"; do
  docker images "$repo" --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -d'|' -f2 \
    | xargs -r docker rmi > /dev/null 2>&1 || true
done

warn_if_drift_left

log "배포 완료 — $TAG"
