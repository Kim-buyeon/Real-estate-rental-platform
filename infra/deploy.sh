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
# 슬롯은 넷이다(INF-01) — 앱 노드 APP-01 · APP-02 에 둘씩. **두 앱 노드 어느 쪽에서든 돌린다**(입구 이중화, #404) — 돌리는 노드가
# 「이 노드(local)」, 다른 쪽이 「상대 노드(remote)」다. 이 노드는 infra/.env 의 APP_PUBLISH_ADDR(자기 사설 IP)로 가린다.
# 앞단 nginx 가 두 노드에 하나씩 있고 둘 다 같은 upstream.conf 로 네 슬롯을 본다(그 파일 머리 주석). 그래서 down 토글 · reload 는
# **두 노드의 nginx 모두에** 한다 — 입구 고정 IP 가 어느 쪽에 있든, 배포 도중 옮겨 가든 교체 중인 슬롯에 요청이 가지 않는다.
# readiness · 관찰은 이 노드에서 슬롯의 사설 IP 로 하고, 상대 노드의 슬롯 교체 · nginx 토글 · 화면 교체는 SSH 로 그 노드의 체크아웃에서 부른다.
#   APP_NODE_SSH   상대 노드에 붙는 SSH 대상(기본 deploy@<상대 사설 IP>). **빈 값으로 두면(APP_NODE_SSH=) 상대 노드를 건너뛴다** —
#                  상대가 없거나 꺼진 구성 호환. 상대 슬롯이 readiness 에 응답하면(떠 있으면) 아무것도 바꾸지 않고 멈춘다
#   APP_NODE_DIR   상대 노드의 체크아웃 안 infra/(기본 /home/deploy/rental/infra — 노드별 체크아웃 자리, 운영 절차서 9장)
#   SELF_ADDR      이 노드의 사설 IP(기본 infra/.env 의 APP_PUBLISH_ADDR)
# SSH 는 비대화식(BatchMode)이다 — 이 노드의 deploy 계정이 상대 노드의 deploy 에 키로 붙을 수 있어야 한다(두 방향 모두 — 운영 절차서).
# 비밀번호 · 호스트 키 확인 질문에서 멈추지 않고 실패한다.
set -euo pipefail
cd "$(dirname "$0")"

TAG=${1:?"사용법: bash deploy.sh <태그> [--rollback]"}
MODE=${2:-}
REGISTRY=${REGISTRY:-ghcr.io/kim-buyeon/real-estate-rental-platform}
APP="$REGISTRY/backend:$TAG"
WEB="$REGISTRY/frontend:$TAG"
UP=./nginx/conf.d/upstream.conf          # 컨테이너에 마운트된 호스트 파일
UP_IN_INFRA=nginx/conf.d/upstream.conf   # 체크아웃 infra/ 기준 경로 — 상대 노드에서 같은 파일을 가리킬 때
# 두 앱 노드의 사설 IP — upstream.conf 의 네 줄과 같은 값이어야 한다(그 파일 머리 주석). 순서는 APP-01 · APP-02
NODE_ADDRS=(10.20.0.10 10.20.1.10)
# 이 노드 — infra/.env 의 APP_PUBLISH_ADDR(운영 Compose 의 app-1 주석 — 슬롯의 게시 주소이자 이 노드의 사설 IP). SELF_ADDR 로 덮을 수 있다
SELF_ADDR=${SELF_ADDR:-$(sed -n 's/^APP_PUBLISH_ADDR=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r"')}
case "$SELF_ADDR" in
  "${NODE_ADDRS[0]}") REMOTE_ADDR=${NODE_ADDRS[1]} ;;
  "${NODE_ADDRS[1]}") REMOTE_ADDR=${NODE_ADDRS[0]} ;;
  *) echo "!!! 이 노드를 가리지 못했다 — infra/.env 의 APP_PUBLISH_ADDR='$SELF_ADDR' 가 ${NODE_ADDRS[*]} 중 하나가 아니다. 아무것도 바꾸지 않는다" >&2
     exit 1 ;;
esac
# :- 가 아니라 - 다 — 빈 값을 「건너뛴다」로 읽어야 하므로 기본값은 변수가 아예 없을 때만 쓴다
APP_NODE_SSH=${APP_NODE_SSH-deploy@$REMOTE_ADDR}
APP_NODE_DIR=${APP_NODE_DIR:-/home/deploy/rental/infra}
# 「이름 주소 포트 위치」. 교체 순서 — 노드마다 한 슬롯씩 번갈아 바꾼다. 어느 순간에도 두 노드 모두에 서비스하는 슬롯이
# 하나씩 남아, 교체 도중 한 노드가 통째로 멈춰도 다른 노드가 받는다. 첫 슬롯(관찰 대상)은 상대 노드의 app-2 다
SLOTS=(
  "app-2 $REMOTE_ADDR 8082 remote"
  "app-2 $SELF_ADDR 8082 local"
  "app-1 $REMOTE_ADDR 8081 remote"
  "app-1 $SELF_ADDR 8081 local"
)
REPO_ROOT=""                             # 드리프트 가드가 채운다. 비어 있으면 가드를 건너뛴 것이다
UP_REL=""                                # 저장소 루트 기준의 UP 경로
DRAIN=${DRAIN:-30}                       # 제외 뒤 진행 중인 요청이 끝나기를 기다리는 초 — graceful 30초와 같다
WARMUP=${WARMUP:-60}                     # 첫 슬롯 복귀 뒤 JIT 워밍업 — 3.3
# 복귀 전 예열 — 슬롯마다 upstream 에 넣기 전에 주요 조회를 직접 보낸다(#273). 부하 중 배포(T6, 2026-09-27)에서 새로 뜬 JVM 이
# 받은 첫 요청들이 최대 4 ~ 6초 걸렸다(p99 는 그대로). readiness 는 앱이 떴다는 것만 보고 JIT · 커넥션 풀은 차갑다.
# 결과는 보지 않는다 — 판정은 readiness · 첫 슬롯 관찰(smoke.sh)이 한다. 0 이면 끈다. 요청마다 상한 3초(readiness 와 같다).
# 경로는 공개 조회 열 개다(#416) — 아래 세 개에 더해 배포 시작 때 pick_warm_paths 가 목록 정렬 둘 · 반경 · 매물 하나의
# 상세 · 위험도 · 등기 · 대장을 붙인다. 매물 경로는 #273 의 세 경로가 닿지 않아 슬롯 교체 뒤 첫 호출이 이후의 2 ~ 6배였다
# (상세 49 → 9 ms · 등기 100 → 18 · 대장 33 → 17, #416).
# 횟수는 20 → 10 이다 — 첫 호출만 차갑고 둘째부터 이후 값이다(위 측정). 10회 × 10경로 = 100건으로 전(20 × 3 = 60건, 슬롯당 5 ~ 9초,
# 운영 절차서 3.4)과 비슷한 양이다. 최악(응답이 매달림) 10회 × 10경로 × 3초 = 300초/슬롯, 패킷이 버려지면 × 2초 = 200초/슬롯 —
# 20회 그대로면 600초/슬롯이었다. 대장 경로를 빼면 9경로 · 270초, 매물을 고르지 못하면 6경로 · 180초/슬롯. 슬롯 넷이면 × 4
# (최악 1,200초 = 20분 — 모든 요청이 상한까지 매달릴 때다. 그 슬롯은 readiness 를 통과한 뒤다)
WARM_ROUNDS=${WARM_ROUNDS:-10}
GANGSEO=%EA%B0%95%EC%84%9C%EA%B5%AC     # 「강서구」 — 고정 경로의 자치구(map-clusters · 대체 반경 · 보증금순 목록)
WARM_PATHS=("/api/properties/district-counts" "/api/properties?size=20"
  "/api/properties/map-clusters?district=$GANGSEO&minLat=37.41&maxLat=37.72&minLng=126.73&maxLng=127.27")
WARM_PICK_TRIES=5                        # 예열 매물 후보 — 대장이 있는 매물을 찾아 위험도를 읽어 보는 최대 수(pick_warm_paths)

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

reload() { docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload; }
# sed · grep 정규식에 넣을 주소 — 점을 글자 그대로
addr_re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }
# 퍼센트 인코딩 — 바이트마다 %XX(자치구 이름). 노드에 jq 가 없고 스크립트가 python 에 기대지 않는다
urlenc() { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n' | sed 's/../%&/g'; }
# 예열 매물 고르기용 조회 — 상한은 예열 요청과 같다. 실패(연결 · 시간 · 4xx · 5xx)면 빈 값 · 비0
warm_get() { curl -fs --connect-timeout 2 -m 3 "$1" 2>/dev/null; }

# 예열 경로를 채운다(#416) — 배포 시작 때 한 번, 아직 서비스 중인(교체 전) 슬롯 하나에 공개 조회를 보낸다. DB 에 직접 붙지 않는다.
#   1) 목록 전세가율 오름차순 첫 페이지 — 전세가율이 없는(판정 없는) 매물은 방향과 무관하게 맨 뒤라(API 명세서 매물 1.3 · 매퍼)
#      앞쪽 항목은 판정이 있다. 그래도 riskGrade · debtRatio 가 채워진 항목만 후보로 본다
#   2) 후보마다 위험도를 읽어 consistency.addressMatched 가 true/false 인 매물(대장이 있다 — 위험도 명세 1.1, null 은 확인 불가)을
#      고른다. 대장 조회(GET)는 대장이 없으면 그 자리에서 건축HUB 에 떼러 간다(LedgerCommandService.collectIfAbsent) — 예열이
#      외부 호출 · 일일 상한 · 쓰기를 만들지 않게, 대장이 있다고 확인된 매물에만 대장 경로를 붙인다. 없으면 대장 경로만 뺀다
#   3) 상세에서 좌표를 읽어 그 자리 반경 1 km. 못 읽으면 고정 좌표(강서구 — 명세 1.4 예시)
# 쓰기 — 판정이 있는 매물의 위험도 · 등기 · 대장 조회는 읽기만 한다. 예외는 판정 입력이 바뀐 매물(재분석 대기 · 기준이나 규칙을
# 바꾼 배포 — 위험도 명세 1.1)로, 첫 위험도 조회가 판정해 저장한다. 그 매물을 처음 조회한 사용자가 만들 쓰기와 같고 한 번뿐이다.
# 고르기 최악 — 목록 요청 슬롯마다 3초, 답한 슬롯에서 위험도 5 × 3초 + 상세 3초. 셋이 매달리고 넷째가 답하면 9 + 3 + 15 + 3 = 30초.
# 못 고르면 매물 경로(상세 · 위험도 · 등기 · 대장)만 빠지고 나머지는 그대로 예열한다. set -e 아래라 실패할 수 있는 줄은 || 로 받는다
pick_warm_paths() {
  local i addr port where base list cands line id risk detail lat lng
  local pick="" dist="" ledger=0 src="" d_enc=$GANGSEO
  for ((i = ${#SLOTS[@]} - 1; i >= 0; i--)); do    # 마지막에 바뀌는 슬롯부터 — 이 노드 app-1
    read -r _ addr port where <<< "${SLOTS[$i]}"
    if [ "$where" = remote ] && [ -z "$APP_NODE_SSH" ]; then continue; fi
    base="http://$addr:$port"
    list=$(warm_get "$base/api/properties?size=20&sort=debtRatio,asc") || continue
    src="$addr:$port"
    # 목록 항목은 중첩 없는 객체다(명세 1.6)
    cands=$(grep -o '{[^{}]*"propertyId":[0-9][^{}]*}' <<< "$list" | grep '"riskGrade":"' | grep '"debtRatio":[0-9]' \
      | head -n "$WARM_PICK_TRIES") || true
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      id=$(sed -n 's/.*"propertyId":\([0-9][0-9]*\).*/\1/p' <<< "$line")
      risk=$(warm_get "$base/api/properties/$id/risk") || continue
      if [ -z "$pick" ]; then               # 대장 있는 후보가 없을 때 쓸 첫 후보
        pick=$id
        dist=$(sed -n 's/.*"district":"\([^"]*\)".*/\1/p' <<< "$line")
      fi
      if grep -Eq '"addressMatched":(true|false)' <<< "$risk"; then
        pick=$id
        dist=$(sed -n 's/.*"district":"\([^"]*\)".*/\1/p' <<< "$line")
        ledger=1
        break
      fi
    done <<< "$cands"
    break                                  # 목록에 답한 슬롯에서 끝낸다 — 후보가 없어도 다른 슬롯은 같은 DB 를 본다
  done

  WARM_PATHS+=("/api/properties?size=20&sort=debtRatio,asc")
  if [ -z "$pick" ]; then
    WARM_PATHS+=("/api/properties?district=$GANGSEO&size=20&sort=deposit,asc"
      "/api/properties?district=$GANGSEO&lat=37.55&lng=126.85&radiusKm=1")
    log "예열 매물을 고르지 못했다(${src:-목록에 답한 슬롯 없음}) — 매물 경로 없이 ${#WARM_PATHS[@]}경로"
    return 0
  fi
  [ -n "$dist" ] && d_enc=$(urlenc "$dist")
  detail=$(warm_get "http://$src/api/properties/$pick") || true
  lat=$(sed -n 's/.*"latitude":\(-\{0,1\}[0-9][0-9.]*\).*/\1/p' <<< "$detail")
  lng=$(sed -n 's/.*"longitude":\(-\{0,1\}[0-9][0-9.]*\).*/\1/p' <<< "$detail")
  if [ -z "$lat" ] || [ -z "$lng" ]; then lat=37.55; lng=126.85; d_enc=$GANGSEO; fi
  # 보증금순은 자치구를 붙인다 — 보증금 인덱스가 없어 자치구 없이는 전 매물을 정렬한다(전세가율순만 인덱스로 끊는다 — 매퍼)
  WARM_PATHS+=("/api/properties?district=$d_enc&size=20&sort=deposit,asc"
    "/api/properties?district=$d_enc&lat=$lat&lng=$lng&radiusKm=1"
    "/api/properties/$pick" "/api/properties/$pick/risk" "/api/properties/$pick/registry")
  if [ "$ledger" -eq 1 ]; then
    WARM_PATHS+=("/api/properties/$pick/ledger")
    log "예열 매물 $pick(대장 있음, $src에서 고름) — ${#WARM_PATHS[@]}경로"
  else
    log "예열 매물 $pick(대장 확인 못 함 — 대장 경로 뺌, $src에서 고름) — ${#WARM_PATHS[@]}경로"
  fi
}

# 상대 노드에서 명령을 돈다. 인자가 원격 셸의 한 줄이 된다 — 값은 부르는 쪽이 printf %q 로 감싼다
# ServerAlive — 연결이 선 뒤 사설망에서 패킷이 버려지면 ssh 가 끝없이 매달린다. 15초 × 4 = 약 60초 무응답이면 끊고 실패한다
remote() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$APP_NODE_SSH" "$@"
}

# down 플래그만 토글한다. 서버 목록 자체는 바꾸지 않는다(3.1). 인자는 주소 · 포트 — 두 노드의 슬롯이 같은 포트를 쓴다.
# 「server 주소:포트」로 찾는다 — 머리 주석에 적힌 주소에 걸리지 않게
# 이미 down 인 줄은 건드리지 않는다 — 실패로 멈춘 슬롯을 롤백할 때 "down down" 이 되지 않게
# 두 노드의 nginx 에 같은 식을 건다(머리 주석). 이 노드 → 상대 노드 순서다. 상대 노드에서 실패하면 0 이 아닌 값을 돌려준다 —
# 부르는 쪽이 멈춘다(이 노드의 nginx 는 이미 바뀌었고, 상대 nginx 는 바뀌지 않았거나 reload 전이다)
toggle_expr_down() { local a; a=$(addr_re "$1"); printf '%s' "/server ${a}:$2 .* down;/!s|\(server ${a}:$2[^;]*\);|\1 down;|"; }
toggle_expr_up()   { local a; a=$(addr_re "$1"); printf '%s' "s|\(server ${a}:$2[^;]*\) down;|\1;|"; }
toggle_remote() {
  [ -n "$APP_NODE_SSH" ] || return 0
  remote "cd $(printf %q "$APP_NODE_DIR") && sed -i $(printf %q "$1") $UP_IN_INFRA && docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload"
}
down_slot() {
  local e; e=$(toggle_expr_down "$1" "$2")
  # if 조건 안에서 불리므로 set -e 가 걸리지 않는다 — 실패를 직접 돌려준다
  { sed -i "$e" "$UP" && reload; } || { log "!!! 이 노드의 nginx 에서 $1:$2 를 빼지 못했다(sed · nginx -t · reload)"; return 1; }
  toggle_remote "$e" || { log "!!! 상대 노드($APP_NODE_SSH)의 nginx 에서 $1:$2 를 빼지 못했다"; return 1; }
}
up_slot() {
  local e; e=$(toggle_expr_up "$1" "$2")
  { sed -i "$e" "$UP" && reload; } || { log "!!! 이 노드의 nginx 에 $1:$2 를 되돌리지 못했다(sed · nginx -t · reload)"; return 1; }
  toggle_remote "$e" || { log "!!! 상대 노드($APP_NODE_SSH)의 nginx 에 $1:$2 를 되돌리지 못했다"; return 1; }
}

# 로컬에 없을 때만 받는다 — 로컬 빌드 이미지로 시험할 수 있게
ensure_image() { docker image inspect "$1" > /dev/null 2>&1 || docker pull "$1"; }
# 상대 노드에서 같은 판정 — 그 노드는 자기 공인 IP(또는 입구 고정 IP)로 레지스트리에서 받는다
ensure_image_remote() {
  local q; q=$(printf %q "$1")
  remote "docker image inspect $q > /dev/null 2>&1 || docker pull $q"
}

# ── 설정 드리프트 가드 ──────────────────────────────────────────────────────────────────────
# 이 스크립트는 upstream.conf 의 down 플래그를 sed 로 토글한다(두 노드 모두 — 머리 주석). 배포가 실패로 멈추면 그 슬롯은 down 인 채 남는데,
# 운영 절차서 3.1 은 Nginx 설정 변경을 "체크아웃 → nginx -t → reload" 로 반영하라고 정한다. 그 체크아웃이 down 을
# 지워 **죽은 슬롯을 upstream 에 되살린다.** 그래서 착수 단계에서 파일이 추적 상태와 같은지 한 번 본다.
# 루프 안이나 종료 시점에는 같은 판정을 하지 않는다 — 스크립트 자신이 파일을 고치므로 배포 도중에는 항상 다르다.

# down 으로 남은 슬롯을 노드 · 이름으로 짚어 준다. 인자는 upstream.conf 내용과 그것이 어느 nginx 의 것인지
report_down_slots() {
  local content=$1 whose=$2 slot name addr port where found=0
  for slot in "${SLOTS[@]}"; do
    read -r name addr port where <<< "$slot"
    if grep -q "server $(addr_re "$addr"):${port}[^;]* down;" <<< "$content"; then
      log "    $whose nginx 에서 down 으로 남은 슬롯: $name ($where · $addr:$port)"
      found=1
    fi
  done
  [ "$found" -eq 1 ] || log "    $whose nginx — down 플래그는 남아 있지 않다. 다른 항목이 바뀌었다 — 차이를 본다"
  return 0
}
# 상대 노드의 upstream.conf 가 추적 상태와 다른가. 다르면 0(참), 같으면 1. 닿지 못하면 2 — 상대 노드 확인 단계가 따로 멈춘다
remote_drift() {
  [ -n "$APP_NODE_SSH" ] || return 1
  local out
  out=$(remote "git -C $(printf %q "$APP_NODE_DIR") status --porcelain -- $UP_IN_INFRA" 2>/dev/null) || return 2
  [ -n "$out" ]
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

  local drift rd=1
  if ! drift=$(git -C "$REPO_ROOT" status --porcelain -- "$UP_REL" 2>/dev/null); then
    REPO_ROOT=""
    log "드리프트 가드 건너뜀 — git status 를 실행하지 못했다. upstream 설정을 눈으로 확인하고 진행한다"
    return 0
  fi
  # 상대 노드도 본다 — 실패로 멈춘 배포는 두 노드의 nginx 모두에 down 을 남긴다(입구 이중화 #404)
  remote_drift && rd=0
  [ -n "$drift" ] || [ "$rd" -eq 0 ] || return 0

  log "!!! upstream 설정이 추적 상태와 다르다. 배포를 중단한다 — 운영 절차서 3.1"
  if [ -n "$drift" ]; then
    log "    대상(이 노드 $SELF_ADDR): $UP_REL"
    report_down_slots "$(cat "$UP")" "이 노드"
    git -C "$REPO_ROOT" --no-pager diff -- "$UP_REL" | sed 's|^|    |'
  fi
  if [ "$rd" -eq 0 ]; then
    log "    대상(상대 노드 $APP_NODE_SSH): $APP_NODE_DIR/$UP_IN_INFRA"
    report_down_slots "$(remote "cat $(printf %q "$APP_NODE_DIR")/$UP_IN_INFRA" 2>/dev/null)" "상대 노드"
  fi
  log "    복구: 먼저 down 슬롯을 되살린다 — bash deploy.sh <직전 태그> --rollback (그 뒤 두 노드의 파일이 추적 상태로 돌아온다)."
  log "          되살릴 수 없으면 원인을 확인한 뒤 어긋난 노드마다 git checkout -- infra/$UP_IN_INFRA 로 되돌리고"
  log "          docker compose exec -T nginx nginx -t && docker compose exec -T nginx nginx -s reload 한다(운영 절차서 3.1)."
  log "          절차서에 없는 상황이라 사람이 판단해 넘어가려면 SKIP_DRIFT_CHECK=1 로 이 가드를 끈다"
  exit 1
}

# 배포가 끝난 뒤의 확인. 실패로 만들지 않는다 — 여기까지 왔으면 서비스는 이미 복구된 상태다
warn_if_drift_left() {
  [ -n "$REPO_ROOT" ] || return 0        # 가드를 건너뛴 환경이면 확인할 수단도 없다
  local drift
  drift=$(git -C "$REPO_ROOT" status --porcelain -- "$UP_REL" 2>/dev/null) || return 0
  if [ -n "$drift" ]; then
    log "경고: 배포는 끝났는데 이 노드의 upstream 설정이 추적 상태와 다르다. down 플래그가 모두 풀렸어야 한다"
    report_down_slots "$(cat "$UP")" "이 노드"
    log "       다음 배포는 착수 단계에서 이 차이 때문에 멈춘다. 지금 확인해 되돌린다(운영 절차서 3.1)"
  fi
  if remote_drift; then
    log "경고: 배포는 끝났는데 상대 노드($APP_NODE_SSH)의 upstream 설정이 추적 상태와 다르다"
    report_down_slots "$(remote "cat $(printf %q "$APP_NODE_DIR")/$UP_IN_INFRA" 2>/dev/null)" "상대 노드"
  fi
  return 0
}

check_upstream_drift

# ── 상대 노드 확인 ────────────────────────────────────────────────────────────────────────
# 슬롯을 하나라도 건드리기 전에 본다 — 도중에 SSH 가 안 되는 것을 알면 한 슬롯이 이미 down 인 채 멈춘다.
if [ -z "$APP_NODE_SSH" ]; then
  log "상대 노드 건너뜀 — APP_NODE_SSH 가 비어 있다(상대 노드가 없거나 꺼진 구성)"
  # 건너뛰어도 되는 것은 원격 슬롯이 요청을 받지 않을 때뿐이다. 하나라도 살아 있으면 그 슬롯은 구버전으로 남고,
  # 배포 끝의 화면 교체가 신버전 화면에서 그 구버전 API 를 부르게 만든다(운영 절차서 3.1 — 화면은 슬롯이 모두 끝난 뒤).
  # 그래서 readiness 를 짧게 한 번 두드려 UP 이면 아무것도 바꾸지 않고 멈춘다. 응답이 없으면(상대 노드 꺼짐) 그대로 진행한다
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
    log "!!! 아무것도 바꾸지 않고 중단한다. 상대 노드가 떠 있으면 APP_NODE_SSH 를 비우지 않고(기본값) 다시 배포한다"
    exit 1
  fi
else
  log "상대 노드 확인 — $APP_NODE_SSH:$APP_NODE_DIR (이 노드 $SELF_ADDR)"
  if ! remote "cd $(printf %q "$APP_NODE_DIR") && test -f docker-compose.yml && test -f .env"; then
    log "!!! 상대 노드에 닿지 못했거나 체크아웃 · .env 가 없다. 아무 슬롯도 건드리지 않고 중단한다"
    log "    상대 노드 없이 배포하려면 APP_NODE_SSH= (빈 값)으로 다시 실행한다 — 그때 나오는 경고를 함께 본다"
    exit 1
  fi
  # 상대 노드의 nginx 가 떠 있어야 토글할 수 있다 — 여기서 보지 않으면 첫 슬롯을 뺀 뒤에야 알게 된다
  if ! remote "cd $(printf %q "$APP_NODE_DIR") && docker compose exec -T nginx nginx -t" > /dev/null 2>&1; then
    log "!!! 상대 노드의 nginx 가 떠 있지 않거나 설정 검사(nginx -t)에 실패한다. 아무 슬롯도 건드리지 않고 중단한다"
    exit 1
  fi
  # 두 노드의 Compose 정의가 다르면 같은 태그라도 슬롯 설정이 다르게 뜬다. 멈추지는 않는다 — 체크아웃을 맞추는 것은 운영 절차다
  LOCAL_REV=$(git rev-parse HEAD 2>/dev/null || echo "?")
  REMOTE_REV=$(remote "git -C $(printf %q "$APP_NODE_DIR") rev-parse HEAD" 2>/dev/null || echo "?")
  if [ "$LOCAL_REV" != "$REMOTE_REV" ]; then
    log "경고: 두 노드의 체크아웃이 다르다 — 이 노드 $LOCAL_REV · 상대 노드 $REMOTE_REV. 슬롯 정의(docker-compose.yml)가 어긋날 수 있다"
  fi
fi

# 배포가 끝난 뒤에만 .env 에 적는다. 중간에 멈추면 .env 는 구버전을 가리키므로, 이후 docker compose up 이 남은 슬롯을
# 신버전으로 바꾸지 않는다. 상대 노드의 .env 도 같은 시점에 적는다(아래).
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
  log "이미지 확인(상대 노드) — $TAG"
  if ! ensure_image_remote "$APP" || ! ensure_image_remote "$WEB"; then
    log "!!! 상대 노드에서 이미지를 확인 · 수신하지 못했다. 아무것도 바꾸지 않고 배포를 중단한다"
    exit 1
  fi
fi

if [ "$WARM_ROUNDS" -gt 0 ]; then
  pick_warm_paths
fi

FIRST=1
for slot in "${SLOTS[@]}"; do
  read -r NAME ADDR PORT WHERE <<< "$slot"
  ID="$NAME@$ADDR"                       # 로그의 슬롯 이름 — 두 노드의 슬롯 이름이 같다

  if [ "$WHERE" = remote ] && [ -z "$APP_NODE_SSH" ]; then
    log "[$ID] 건너뜀 — APP_NODE_SSH 가 비어 있다"
    continue
  fi

  log "[$ID] upstream 제외(두 노드의 nginx) 후 drain ${DRAIN}초"
  if ! down_slot "$ADDR" "$PORT"; then
    log "!!! [$ID] 두 노드의 nginx 에서 함께 빼지 못했다(위 출력). 그대로 두고 배포를 중단한다 — 두 노드의 upstream.conf 를 확인한다"
    exit 1
  fi
  sleep "$DRAIN"

  log "[$ID] 이미지 교체"
  if [ "$WHERE" = remote ]; then
    if ! remote "cd $(printf %q "$APP_NODE_DIR") && APP_IMAGE=$(printf %q "$APP") docker compose up -d --no-deps --force-recreate $NAME"; then
      log "!!! [$ID] 상대 노드에서 교체하지 못했다(SSH · docker compose 실패). down 상태를 유지하고 배포를 중단한다"
      exit 1
    fi
  else
    APP_IMAGE="$APP" docker compose up -d --no-deps --force-recreate "$NAME"
  fi

  # 원격 슬롯도 이 노드에서 본다 — nginx 가 요청을 보내는 길(사설망)과 같은 길이다
  log "[$ID] readiness 대기"
  for i in $(seq 1 60); do
    # 시간 상한 — 사설망에서 패킷이 버려지면 상한 없는 curl 은 회당 수십 초 매달린다. 상한을 두어 60회 루프의 최악이 약 240 ~ 300초로 묶인다(루프백은 약 120초 — 운영 절차서 3.2)
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

  if [ "$WARM_ROUNDS" -gt 0 ]; then
    log "[$ID] 예열 ${WARM_ROUNDS}회 × ${#WARM_PATHS[@]}경로"
    for i in $(seq 1 "$WARM_ROUNDS"); do
      for p in "${WARM_PATHS[@]}"; do
        curl -s -o /dev/null --connect-timeout 2 -m 3 "http://$ADDR:$PORT$p" || true
      done
    done
  fi

  log "[$ID] upstream 복귀(두 노드의 nginx)"
  if ! up_slot "$ADDR" "$PORT"; then
    log "!!! [$ID] 두 노드의 nginx 에 함께 되돌리지 못했다(위 출력). 배포를 중단한다 — 두 노드의 upstream.conf 를 확인한다"
    exit 1
  fi
done

# 화면은 슬롯이 모두 신버전이 된 뒤에 바꾼다 — 중간에 멈추면 신버전 화면이 구버전 API 를 부르게 된다.
# 슬롯이 하나라 교체 순간 짧게 끊길 수 있다. 상태가 없어 다시 요청하면 된다.
# 두 노드에 하나씩이다(#404) — 이 노드 것을 먼저, 상대 노드 것을 다음에.
log "[web] 교체(이 노드)"
WEB_IMAGE="$WEB" docker compose up -d --no-deps web
WEB_REMOTE_FAILED=0
if [ -n "$APP_NODE_SSH" ]; then
  log "[web] 교체(상대 노드)"
  if ! remote "cd $(printf %q "$APP_NODE_DIR") && WEB_IMAGE=$(printf %q "$WEB") docker compose up -d --no-deps web"; then
    WEB_REMOTE_FAILED=1
    log "!!! 상대 노드의 web 을 바꾸지 못했다 — 입구가 그 노드로 옮겨 가면 옛 화면이 나간다. 끝에서 비0 으로 끝낸다"
  fi
fi

set_env APP_IMAGE "$APP"
set_env WEB_IMAGE "$WEB"
# 여기서 실패하면 슬롯은 이미 모두 신버전인데 상대 노드의 .env 만 옛 태그다 — 그 노드에서 docker compose up 을 다시 부르면 슬롯이
# 구버전으로 돌아간다. 멈추지 않고 알린 뒤 정리 · 드리프트 확인까지 마치고 비0 으로 끝낸다
ENV_REMOTE_FAILED=0
if [ -n "$APP_NODE_SSH" ]; then
  if ! set_env_remote APP_IMAGE "$APP" || ! set_env_remote WEB_IMAGE "$WEB"; then
    ENV_REMOTE_FAILED=1
    log "!!! 상대 노드의 .env 에 APP_IMAGE · WEB_IMAGE 를 적지 못했다. 슬롯은 모두 신버전이지만 그 노드의 .env 는 옛 태그를 가리킨다"
    log "    복구(이 노드에서): ssh $APP_NODE_SSH \"cd $APP_NODE_DIR && sed -i -e 's|^APP_IMAGE=.*|APP_IMAGE=$APP|' -e 's|^WEB_IMAGE=.*|WEB_IMAGE=$WEB|' .env && grep -E '^(APP|WEB)_IMAGE=' .env\""
    log "    (.env 에 그 줄이 없으면 echo 로 더한다). 고치기 전에는 상대 노드에서 docker compose up 을 부르지 않는다"
  fi
fi

# 최근 태그 3개(지금 것 포함)를 남기고 지운다(3.4). 실행 중인 이미지는 docker 가 지우지 않는다
for repo in "$REGISTRY/backend" "$REGISTRY/frontend"; do
  docker images "$repo" --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -d'|' -f2 \
    | xargs -r docker rmi > /dev/null 2>&1 || true
done
# 상대 노드도 같은 규칙이다. 정리 실패는 배포 실패로 만들지 않는다
if [ -n "$APP_NODE_SSH" ]; then
  for repo in "$REGISTRY/backend" "$REGISTRY/frontend"; do
    remote "docker images $(printf %q "$repo") --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' | sort -r | tail -n +4 | cut -d'|' -f2 | xargs -r docker rmi > /dev/null 2>&1" || true
  done
fi

warn_if_drift_left

if [ "$ENV_REMOTE_FAILED" -eq 1 ] || [ "$WEB_REMOTE_FAILED" -eq 1 ]; then
  log "!!! 배포는 끝났으나 상대 노드의 web 교체 또는 .env 기록이 실패했다 — 위 출력대로 고친다"
  exit 1
fi

log "배포 완료 — $TAG"
