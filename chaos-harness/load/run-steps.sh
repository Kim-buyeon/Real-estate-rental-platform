#!/usr/bin/env bash
# T2 한 회차 — 토큰 풀 → SSE 250 연결(xk6-sse 빌드 바이너리)을 유지한 채 steps.js(THINK=0). SSE 가 떠 있지 않으면 멈춘다
# 사용: run-steps.sh <회차 이름>   (LOAD-01 의 ~/load 에서. K6_SSE 는 xk6-sse 로 빌드한 바이너리, 기본 /tmp/k6-sse)
# 결과: results/<이름>.marks · <이름>.log · <이름>.csv.gz · <이름>-sse.log — 단계 집계는 tools/stages.py
set -u
cd ~/load
N=$1
export LT_PASSWORD=$(cat .ltpw)
k6 run -q -e LT_PASSWORD=$LT_PASSWORD -e START=1    -e COUNT=100 -e OUT=tokens.json     -e CONCURRENCY=3 make-tokens.js > results/$N-tok.log 2>&1
# SSE 풀은 VU 수만큼 있어야 한다 — 앞단 상한에 가입이 429 로 빠지면 다시 돈다(이미 있는 계정은 로그인만 한다)
for try in 1 2 3; do
  k6 run -q -e LT_PASSWORD=$LT_PASSWORD -e START=1001 -e COUNT=250 -e OUT=tokens-sse.json -e CONCURRENCY=3 make-tokens.js >> results/$N-toksse.log 2>&1
  [ "$(python3 -c 'import json;print(len(json.load(open("tokens-sse.json"))["tokens"]))')" -ge 250 ] && break
  sleep 10
done
echo "tokens done $(date +%T)" > results/$N.marks
${K6_SSE:-/tmp/k6-sse} run -q -e SSE_VUS=250 -e DURATION=40m -e TOKENS=tokens-sse.json -e SUMMARY_DIR=results sse.js > results/$N-sse.log 2>&1 &
sleep 90
if ! pgrep -f "k6-sse run" >/dev/null; then echo "sse not running $(date +%T)" >> results/$N.marks; exit 1; fi
echo "sse up $(grep -c . results/$N-sse.log) log lines" >> results/$N.marks
echo "steps start $(date +%T)" >> results/$N.marks
k6 run -e THINK=0 -e SUMMARY_DIR=results --out csv=results/$N.csv.gz steps.js > results/$N.log 2>&1
echo "steps end $(date +%T)" >> results/$N.marks
wait
echo "all end $(date +%T)" >> results/$N.marks
