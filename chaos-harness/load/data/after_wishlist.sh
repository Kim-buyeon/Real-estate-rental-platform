#!/usr/bin/env bash
# 관심 매물 구간 반복이 끝나면 소유자 불일치 보정 → 통계 갱신 → 검증 (#376, 알림은 크레딧 회복 뒤 따로)
set -uo pipefail
cd "$(dirname "$0")"
while pgrep -f '[r]un.sh wishlist' >/dev/null; do sleep 20; done
./run.sh owner-fix && ./run.sh analyze && ./run.sh verify
echo "[$(date '+%F %T')] 전체 끝 (알림 제외, 종료 코드 $?)"
