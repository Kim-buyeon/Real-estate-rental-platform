#!/usr/bin/env bash
# 매물 반영 반복(run.sh all · fake_follow.sh)이 모두 끝나면 사용자 축 → 통계 갱신 → 검증을 잇는다 (#376).
set -uo pipefail
cd "$(dirname "$0")"
while pgrep -f '[r]un.sh all|[f]ake_follow' >/dev/null; do sleep 30; done
echo "[$(date '+%F %T')] 매물 반복 끝 — 사용자 축 시작"
./run.sh users && ./run.sh analyze && ./run.sh verify
echo "[$(date '+%F %T')] 전체 끝 (종료 코드 $?)"
