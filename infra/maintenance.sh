#!/usr/bin/env bash
# 점검 모드 — 운영 절차서 6장(페일오버 2단계). infra/ 에서 실행한다.
#
#   bash maintenance.sh on | off | status
#
# 표시 파일 하나로 켜고 끈다. Nginx 서버 블록이 요청마다 파일 유무를 보고 전 경로에 503 을 돌려준다(conf.d/default.conf).
# reload 가 필요 없고, location 보다 먼저 판정되므로 /api/ 쓰기도 막힌다.
set -euo pipefail
cd "$(dirname "$0")"
FLAG=./nginx/maintenance/on

case "${1:-}" in
  on)     touch "$FLAG"; echo "점검 모드 켬 — 전 경로 503" ;;
  off)    rm -f "$FLAG"; echo "점검 모드 끔" ;;
  status) [ -f "$FLAG" ] && echo "켜짐" || echo "꺼짐" ;;
  *)      echo "사용법: bash maintenance.sh on | off | status"; exit 2 ;;
esac
