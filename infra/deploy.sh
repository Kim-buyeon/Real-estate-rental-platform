#!/usr/bin/env bash
# 슬롯 순차 교체 배포 — 운영 절차서 3장. infra/ 에서 실행한다.
#
#   bash deploy.sh <태그>              일상 배포. 첫 슬롯 복귀 뒤 관찰(smoke.sh)한다
#   bash deploy.sh <태그> --rollback   롤백. 되돌아갈 태그는 이미 검증된 버전이라 관찰을 건너뛴다(3.4)
#
# 태그는 커밋 해시다(image.yml). 순서: 제외 → drain → 교체 → readiness → 복귀. 실패하면 그 슬롯을 down 으로 두고 멈춘다 —
# 남은 슬롯이 구버전으로 계속 서비스한다(3.1).
set -euo pipefail
cd "$(dirname "$0")"

TAG=${1:?"사용법: bash deploy.sh <태그> [--rollback]"}
MODE=${2:-}
REGISTRY=${REGISTRY:-ghcr.io/kim-buyeon/real-estate-rental-platform}
APP="$REGISTRY/backend:$TAG"
WEB="$REGISTRY/frontend:$TAG"
UP=./nginx/conf.d/upstream.conf          # 컨테이너에 마운트된 호스트 파일
SLOTS=("app-2 8082" "app-1 8081")
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

log "배포 완료 — $TAG"
