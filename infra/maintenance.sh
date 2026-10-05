#!/usr/bin/env bash
# 점검 모드 — 운영 절차서 6장(페일오버 2단계). infra/ 에서 실행한다.
#
#   bash maintenance.sh on | off | status
#
# 표시 파일 하나로 켜고 끈다. Nginx 서버 블록이 요청마다 파일 유무를 보고 전 경로에 503 을 돌려준다(conf.d/default.conf).
# reload 가 필요 없고, location 보다 먼저 판정되므로 /api/ 쓰기도 막힌다.
#
# 입구 이중화(#404) — 앞단 Nginx 가 두 앱 노드에 하나씩 있다. 한쪽만 켜면 점검 중에 입구 고정 IP 가 다른 노드로 옮겨 가는 순간
# 쓰기가 다시 들어온다. 그래서 **상대 노드의 표시 파일도 함께 켜고 끈다**(SSH — 배포 스크립트와 같은 노드 간 경로).
#   APP_NODE_SSH   상대 노드 SSH 대상(기본 deploy@<상대 사설 IP> — 이 노드는 infra/.env 의 APP_PUBLISH_ADDR 로 가린다, deploy.sh 와 같다).
#                  빈 값(APP_NODE_SSH=)이면 이 노드만 — 상대가 꺼져 있거나 단일 입구 구성
#   APP_NODE_DIR   상대 노드의 체크아웃 안 infra/(기본 /home/deploy/rental/infra)
# 상대 노드에 닿지 못하면 이 노드만 바꾸고 0 이 아닌 값으로 끝난다 — 어긋난 채 두지 않도록 알린다.
# 입구 감시(infra/os/entry/entry-watch.sh)는 503 을 「입구가 산 것」으로 보므로 점검 모드가 고정 IP 를 옮기게 만들지 않는다.
set -euo pipefail
cd "$(dirname "$0")"
FLAG=./nginx/maintenance/on
FLAG_IN_INFRA=nginx/maintenance/on

NODE_ADDRS=(10.20.0.10 10.20.1.10)
SELF_ADDR=${SELF_ADDR:-$(sed -n 's/^APP_PUBLISH_ADDR=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r"')}
case "$SELF_ADDR" in
  "${NODE_ADDRS[0]}") PEER=${NODE_ADDRS[1]} ;;
  "${NODE_ADDRS[1]}") PEER=${NODE_ADDRS[0]} ;;
  *) PEER="" ;;   # 단일 노드 · 3노드 — 상대가 없다
esac
APP_NODE_SSH=${APP_NODE_SSH-${PEER:+deploy@$PEER}}
APP_NODE_DIR=${APP_NODE_DIR:-/home/deploy/rental/infra}

# 상대 노드에서 한 줄을 돈다. 인자 둘째는 실패 안내에 쓸 하위 명령
remote() {
  [ -n "$APP_NODE_SSH" ] || return 0
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$APP_NODE_SSH" "cd $(printf %q "$APP_NODE_DIR") && $1" \
    || { echo "!!! 상대 노드($APP_NODE_SSH)에 반영하지 못했다 — 이 노드만 바뀌었다. 상대 노드에서 bash maintenance.sh $2 를 직접 돌린다" >&2; return 1; }
}

case "${1:-}" in
  on)     touch "$FLAG"; echo "점검 모드 켬 — 전 경로 503"
          remote "touch $FLAG_IN_INFRA" on
          [ -z "$APP_NODE_SSH" ] || echo "상대 노드($APP_NODE_SSH)도 켬" ;;
  off)    rm -f "$FLAG"; echo "점검 모드 끔"
          remote "rm -f $FLAG_IN_INFRA" off
          [ -z "$APP_NODE_SSH" ] || echo "상대 노드($APP_NODE_SSH)도 끔" ;;
  status) if [ -f "$FLAG" ]; then echo "이 노드 켜짐"; else echo "이 노드 꺼짐"; fi
          remote "if [ -f $FLAG_IN_INFRA ]; then echo '상대 노드 켜짐'; else echo '상대 노드 꺼짐'; fi" status || true ;;
  *)      echo "사용법: bash maintenance.sh on | off | status"; exit 2 ;;
esac
