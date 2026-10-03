#!/usr/bin/env bash
# 가짜 매물 반영을 실매물 반영 뒤에 따라 붙인다 (#376). 같은 구를 두 반복이 동시에 건드리면 적재용 표의 식별자 배정이 겹쳐
# 매물이 중복될 수 있으므로, 실매물 로그에 「<구> 끝」이 찍힌 구만 처리한다.
#   ./fake_follow.sh <fake_limit> <실매물 로그>...
set -euo pipefail
cd "$(dirname "$0")"
limit=$1; shift
logs=("$@")
DISTRICTS=(종로구 중구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 강서구
           구로구 금천구 영등포구 동작구 관악구 서초구 강남구 송파구 강동구)
for d in "${DISTRICTS[@]}"; do
  until grep -q "== $d 끝" "${logs[@]}" 2>/dev/null; do sleep 20; done
  ./run.sh district "$d" "$limit"
done
echo "[$(date '+%F %T')] 가짜 반영 끝"
