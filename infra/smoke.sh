#!/usr/bin/env bash
# 관찰 판정 — 운영 절차서 3.3. 통과 0, 이상 1.
#
#   bash smoke.sh <포트>          이 노드의 슬롯(127.0.0.1)
#   bash smoke.sh <주소> <포트>   다른 노드의 슬롯 — APP-02 의 슬롯은 그 노드의 사설 IP 에 게시된다(INF-01)
#
# 관측 스택(INF-05)이 차기 범위라 지표 대신 **교체한 슬롯을 직접 두드린다.** upstream 에 넣기 전에 대표 조회 경로를 N번 불러
# 5xx 비율과 p95 를 본다. 기준은 옛 observe.sh 와 같다 — 5xx 1% 미만, p95 1초 미만.
# 표본을 스스로 만들므로 「트래픽이 적어 판정 불가」가 없다.
set -uo pipefail

USAGE="사용법: bash smoke.sh [<주소>] <포트>"
if [ "$#" -ge 2 ]; then
  ADDR=$1
  PORT=$2
else
  ADDR=127.0.0.1
  PORT=${1:?$USAGE}
fi
N=${N:-60}
PATHS=("/api/properties/district-counts" "/api/properties?size=20")
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

for i in $(seq 1 "$N"); do
  p=${PATHS[$((i % ${#PATHS[@]}))]}
  # 연결 실패 · 시간 초과에도 curl 은 -w 로 "000 <시간>" 한 줄을 쓴다. 종료 코드만 흡수한다
  curl -s -o /dev/null --max-time 5 -w '%{http_code} %{time_total}\n' "http://$ADDR:$PORT$p" >> "$OUT" || true
done

TOTAL=$(wc -l < "$OUT")
# 연결 실패(000)도 실패로 센다
ERR=$(awk '$1 == "000" || $1 >= 500' "$OUT" | wc -l)
P95=$(awk '{print $2}' "$OUT" | sort -n | awk '{a[NR]=$1} END {i=int(NR*0.95); if (i<1) i=1; print a[i]}')

printf '요청 %d건  실패(5xx · 연결) %d건  p95 %ss\n' "$TOTAL" "$ERR" "$P95"
awk -v r="$TOTAL" -v e="$ERR" -v p="$P95" 'BEGIN { exit !(e / r < 0.01 && p < 1.0) }'
