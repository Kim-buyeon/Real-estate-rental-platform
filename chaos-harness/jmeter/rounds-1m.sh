#!/usr/bin/env bash
# 매물 100만 부하 · 스트레스 회차를 이어 돌린다(INF-06 #390). 회차마다 측정 README 「회차마다」의 1 ~ 5(pre-round · record start ·
# run.sh · record stop · collect)를 부르고, 회차 사이를 GAP_S(기본 30초) 둔다. 회차 목록 · 판정 · 시간은 이 파일 아래 표가 정본이다.
#
#   bash rounds-1m.sh [--phase load|stress|all] [--round <이름>]… [--soak] [--redo] [--plan] [--list]
#
#   --phase        load(부하 자동 회차) · stress(스트레스 자동 회차 — 부하에서 합격한 것만) · all(기본 — load 다음 stress)
#   --round <이름> 그 회차만(여러 번 줄 수 있다). 운영자 회차(op — 조건을 사람이 바꾼다)와 S-429 는 이것으로만 돈다
#   --soak         지속 회차(SOAK, 30 RPS × 30분)를 맨 끝에 더한다. 기본 목록에는 없다
#   --redo         CSV 에 이미 있는 회차도 다시 돈다(기본은 skip 이 아닌 줄이 있으면 건너뛴다 — 중간에 끊겨도 같은 명령으로 이어서 돈다)
#   --plan         돌지 않고 고른 회차와 예상 시간만 보인다.   --list  전체 회차 표와 예상 시간
#
# 회차마다 자동 중단(그 회차만 멈추고 다음으로) — 10초 창(표본 완료 시각 기준, setup 레이블 제외, 창 표본 MIN_WIN 이상)에서
#   ① p95 > P95_MS(500) 가 두 창 연속 ② 5xx 비율 > ERR5XX_PCT(1 %) 가 두 창 연속 ③ 앱 노드 MemAvailable < MEM_MIN_MB(250) ·
#   메모리를 세 번 연속 읽지 못함(10/4 메모리 고갈 때 SSH 가 먼저 멎었다). 회차 시작 전에도 ③ 을 보고, 걸리면 목록 전체를 멈춘다 —
#   슬롯 재시작(메모리 회수)은 운영 작업이다(#390 계획 변경 5).
# 판정(CSV status) — pass: 중단 없음 · p95 ≤ 500 ms · 오류 ≤ 1 % · 처리량 ≥ 95 %(고정 구간) / fail: 그 밖 /
#   limit: 스트레스의 증가 회차(line · step)가 중단됨 — limit_rps = 중단 시각의 계획 초당 요청 수 / skip: 앞 회차가 합격하지 않음.
#   재시작 0 은 여기서 보지 않는다(pre-round 의 슬롯 기록 · 집계에서 본다).
# 스트레스 회차는 req 칸의 회차가 모두 CSV 에서 pass 일 때만 돈다.
#
# 남기는 것 — $ROUNDS_CSV(기본 results/rounds-1m.csv) 한 줄씩, 회차 결과는 results/<회차>/(run.sh · record.sh 와 같다),
#   진행 로그는 results/<회차>/rounds-1m.log. 회차 폴더가 있는데 CSV 줄이 없으면(도중에 끊김) <회차>.cut-<시각> 으로 옮기고 다시 돈다.
#
# 바꿀 수 있는 값(환경 변수) — run.sh · lib.sh 의 값(HOST · PORT · PROTOCOL · ACCOUNTS_DIR · JMETER · SSH_CONFIG · APP_NODES …)은 그대로 넘어간다
#   GAP_S(30) · P95_MS(500) · ERR5XX_PCT(1) · MIN_WIN(10) · MEM_MIN_MB(250) · MEM_EVERY(10초) · SSE_N(242) · STRESS_MULT(10 — 단독 스트레스의
#   끝 = 부하 회차 초당 요청 수 × 이 값) · MEASURE(full — pre-round · record · collect 를 부른다 / none — JMeter 만, 로컬 스모크)
#   MAX_THREADS(run.sh 기본 300) · ROUNDS_CSV
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
NODE_BIN=$HERE/../measure/node
# shellcheck source=../measure/node/lib.sh
. "$NODE_BIN/lib.sh"

STABLE=${STABLE:-30}
GAP_S=${GAP_S:-30} P95_MS=${P95_MS:-500} ERR5XX_PCT=${ERR5XX_PCT:-1} MIN_WIN=${MIN_WIN:-10}
MEM_MIN_MB=${MEM_MIN_MB:-250} MEM_EVERY=${MEM_EVERY:-10} SSE_N=${SSE_N:-242} STRESS_MULT=${STRESS_MULT:-10}
MEASURE=${MEASURE:-full}
ROUNDS_CSV=${ROUNDS_CSV:-$RESULTS_DIR/rounds-1m.csv}
PLAN_FILE=$HERE/endpoints.jmx

# ── 회차 표 ─────────────────────────────────────────────────────────────────────────────────────────────────
# 이름|단계|대상|부하 계단|선행 합격|JMeter 추가 인자|NOTES|WARMUP_SEC|종류
#   단계 load · stress · soak, 종류 auto(목록에 든다) · op(운영자가 조건을 바꾸고 --round 로) · ip(다른 주소에서 --round 로) · flag(--soak)
# 단독 부하의 초당 요청 수 = max(혼합 비율 × 60, 5) 의 반올림(ENDPOINTS.md 3장). 길이는 #390 계획(10/5 마감 조정 — 사이 30초)
P=60
U=(  # 번호 대상 초당요청
  "01 map-clusters 19"   "02 property-detail 8"   "03 properties-list 8"   "04 property-risk 7"   "05 loans-limit 7"
  "06 notifications 5"   "07 district-counts 5"   "08 properties-radius 5" "09 ledger 5"          "10 registry 5"
  "11 wishlist-read 5"   "12 profile-read 5"      "13 subscriptions-read 5" "14 stream-ticket 5"  "15 reissue 5"
  "16 wishlist-write 5"  "17 profile-write 5"     "18 subscriptions-write 5" "19 notification-read 5" "20 notification-read-all 5"
  "21 login 5"           "22 logout 5")
ROUNDS=()
for u in "${U[@]}"; do
  read -r n tg r <<< "$u"
  ROUNDS+=("L-U$n|load|$tg|line(1,$r,30s) const($r,2m)|||단독 부하 $r RPS|30|auto")
done
MIXRAMP="line(1,$P,30s)"
ROUNDS+=(
  "L-R01|load|mix|$MIXRAMP const($P,270s)|||혼합 흔들림 1|30|auto"
  "L-R02|load|mix|$MIXRAMP const($P,270s)|||혼합 흔들림 2|30|auto"
  "L-R03|load|mix|$MIXRAMP const($P,270s)|||혼합 흔들림 3|30|auto"
  "L-R04|load|mix|$MIXRAMP const($P,870s)||-Jsse=$SSE_N|혼합 본 회차 + SSE $SSE_N|30|auto"
  "L-R10|load|mix|$MIXRAMP const($P,270s)||-Jsse=$SSE_N|추적 끔(운영자가 끄고 돌린다)|30|op"
  "L-R11|load|mix|$MIXRAMP const($P,270s)||-Jsse=$SSE_N|읽기 분산 끔(운영자가 끄고 돌린다)|30|op"
  "L-R12|load|mix|$MIXRAMP const($P,270s)||-Jsse=$SSE_N|배포 직후(운영자가 배포 직후 돌린다)|30|op"
)
for u in "${U[@]}"; do
  read -r n tg r <<< "$u"
  ROUNDS+=("S-U$n|stress|$tg|line($r,$((r * STRESS_MULT)),3m)|L-U$n||단독 스트레스 $r → $((r * STRESS_MULT)) RPS(3분 연속 증가)|0|auto")
done
ROUNDS+=(
  "S8|stress|mix|step($P,$((P * 4)),15,3m)|L-R04||혼합 계단 +15 RPS / 3분 — 무너질 때까지(상한 4P, 중단 조건이 먼저 끊는다)|0|auto"
  "S2|stress|mix|line(1,$((P * 2)),30s) const($((P * 2)),270s)|L-R04||혼합 2배|30|auto"
  "S3|stress|mix|$MIXRAMP const($P,270s)|L-R04|-Jskew_pct=60|쏠림 — 요청 60 % 를 한 자치구 · 같은 화면|30|auto"
  "S4|stress|mix|const($P,3m)|L-R04 L-U21|-Jlogin_burst=242 -Jlogin_burst_s=10 -Jburst_delay_s=30|로그인 몰림 242 / 10초(30초 뒤) + 혼합 $P|0|auto"
  "S7|stress|mix|$MIXRAMP const($P,270s)|L-R04|-Jconnection=close -Jsse=1000|keep-alive 없음 + SSE 1000|30|auto"
  "S-SPIKE|stress|mix|line(1,$((P * 3)),10s) const($((P * 3)),170s)|L-R04||급증 0 → $((P * 3)) RPS / 10초|0|auto"
  "S5|stress|mix|const($P,5m)|L-R04||캐시 비움(운영자가 비우고 바로 돌린다)|0|op"
  "S6|stress|mix|$MIXRAMP const($P,570s)|L-R04||배치 동시(운영자가 배치를 켜고 돌린다)|30|op"
  "S-429|stress|district-counts|const(30,2m)|||상한 확인 — 예외 아닌 주소(운영자 PC)에서, HOST=입구 공인 IP|0|ip"
  "SOAK|soak|mix|line(1,30,30s) const(30,1770s)|||지속 30 RPS(무인)|30|flag"
  # 10/5 사용자 결정 — 혼합 부하(60 RPS) 불합격 뒤: 혼합 한계는 20 RPS 부터 따로 재고, 비교 · 캐시 · 배치 · 지속은 안정 부하 STABLE 에서
  "S8L|stress|mix|step(20,$P,5,3m)|||혼합 한계 탐색 — 20 부터 +5 RPS / 3분(혼합 부하 불합격 항목의 실제 한계)|0|op"
  "S8M|stress|mix|step(10,20,2,3m)|||혼합 한계 탐색 — 10 부터 +2 RPS / 3분(S8L 이 20 에서 기준 초과)|0|op"
  "B-R04|load|mix|line(1,$STABLE,30s) const($STABLE,270s)||-Jsse=$SSE_N|안정 부하 기준(측정 모드 켬)|30|op"
  "B-R12|load|mix|line(1,$STABLE,30s) const($STABLE,270s)||-Jsse=$SSE_N|안정 부하 · 배포 직후|30|op"
  "B-R10|load|mix|line(1,$STABLE,30s) const($STABLE,270s)||-Jsse=$SSE_N|안정 부하 · 추적 끔|30|op"
  "B-R11|load|mix|line(1,$STABLE,30s) const($STABLE,270s)||-Jsse=$SSE_N|안정 부하 · 읽기 분산 끔|30|op"
  "B-S5|stress|mix|const($STABLE,5m)|||안정 부하 · 캐시 비움 직후|0|op"
  "B-S6|stress|mix|line(1,$STABLE,30s) const($STABLE,570s)|||안정 부하 · 배치 동시|30|op"
  "B-SOAK|soak|mix|line(1,$STABLE,30s) const($STABLE,7170s)|||안정 부하 지속 2시간(무인)|30|op"
)

field() { local IFS='|'; read -r -a F <<< "$1"; printf '%s' "${F[$2]}"; }

# 계단 총길이(초) — run.sh 와 같은 해석(const · line · step)
profile_s() {
  "$PY" - "$1" <<'EOF'
import re, sys
def sec(s):
    t, c = 0, ""
    for ch in s.strip():
        if ch.isdigit(): c += ch; continue
        t += int(c) * {"s": 1, "m": 60, "h": 3600}[ch]; c = ""
    return t + (int(c) if c else 0)
tot = 0
for chunk in sys.argv[1].split(")"):
    if not chunk.strip(): continue
    p = [x.strip() for x in re.split(r"[(,]", chunk)]
    k = p[0].lower()
    if k == "const": tot += sec(p[2])
    elif k == "line": tot += sec(p[3])
    elif k == "step":
        a, b, s = int(p[1]), int(p[2]), int(p[3]); tot += ((b - a) // s + 1) * sec(p[4])
print(tot)
EOF
}

PY=""
for c in python3 python; do
  if command -v "$c" > /dev/null 2>&1 && "$c" -c 'import json' > /dev/null 2>&1; then PY=$c; break; fi
done
export PYTHONIOENCODING=utf-8
[ -n "$PY" ] || die "python3(또는 python)이 없다"
npath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# ── 인자 ──────────────────────────────────────────────────────────────────────────────────────────────────
PHASE=all ONLY=() SOAK=0 REDO=0 MODE=run
while [ $# -gt 0 ]; do
  case $1 in
    --phase) PHASE=${2:?}; shift 2 ;;
    --round) ONLY+=("${2:?}"); shift 2 ;;
    --soak) SOAK=1; shift ;;
    --redo) REDO=1; shift ;;
    --plan) MODE=plan; shift ;;
    --list) MODE=list; shift ;;
    *) die "모르는 인자: $1 — 파일 머리 주석" ;;
  esac
done
case $PHASE in load|stress|all) ;; *) die "--phase 는 load · stress · all" ;; esac

# 고른 회차
SEL=()
for r in "${ROUNDS[@]}"; do
  name=$(field "$r" 0) ph=$(field "$r" 1) kind=$(field "$r" 8)
  if [ ${#ONLY[@]} -gt 0 ]; then
    for o in "${ONLY[@]}"; do [ "$o" = "$name" ] && SEL+=("$r"); done
  elif [ "$MODE" = list ]; then
    SEL+=("$r")
  elif [ "$kind" = auto ] && { [ "$PHASE" = all ] || [ "$PHASE" = "$ph" ]; }; then
    SEL+=("$r")
  elif [ "$kind" = flag ] && [ "$SOAK" = 1 ]; then
    SEL+=("$r")
  fi
done
if [ ${#ONLY[@]} -gt 0 ]; then
  for o in "${ONLY[@]}"; do printf '%s\n' "${SEL[@]}" | grep -q "^$o|" || die "회차 표에 없다: $o"; done
fi
[ ${#SEL[@]} -gt 0 ] || die "고른 회차가 없다"

# ── 예상 시간 — 계단 길이 + 사이 GAP_S. 측정 스크립트(pre-round · record · collect)가 사이보다 길면 그만큼 늘어난다 ─────────────
fmt() { printf '%dh%02dm' $(($1 / 3600)) $((($1 % 3600 + 30) / 60)); }
estimate() {
  local tot=0 n=0 r d k
  declare -A by=()
  for r in "$@"; do
    d=$(profile_s "$(field "$r" 3)"); k=$(field "$r" 8)
    tot=$((tot + d)); n=$((n + 1)); by[$k]=$(( ${by[$k]:-0} + d ))
    [ "$MODE" = run ] || printf '  %-8s %-6s %-22s %5ss  %-5s %s\n' "$(field "$r" 0)" "$(field "$r" 1)" "$(field "$r" 2)" "$d" "$k" "$(field "$r" 6)"
  done
  tot=$((tot + (n > 0 ? n - 1 : 0) * GAP_S))
  log "예상 $(fmt $tot) — 회차 $n · 사이 ${GAP_S}초 × $((n > 0 ? n - 1 : 0)) 포함(측정 스크립트 시간 · 스트레스 조기 중단은 빼고 셈)"
  for k in "${!by[@]}"; do log "  종류 $k: 계단 $(fmt ${by[$k]})"; done
}
if [ "$MODE" != run ]; then
  estimate "${SEL[@]}"
  if [ "$MODE" = list ]; then
    TONIGHT=()
    for r in "${ROUNDS[@]}"; do [ "$(field "$r" 8)" = flag ] || TONIGHT+=("$r"); done
    MODE=quiet; log "오늘 밤 전체(auto + op + ip, 지속 제외):"; estimate "${TONIGHT[@]}"
  fi
  exit 0
fi

# ── 실행 ──────────────────────────────────────────────────────────────────────────────────────────────────
mkdir -p "$(dirname "$ROUNDS_CSV")"
[ -s "$ROUNDS_CSV" ] || echo "round,phase,target,profile,status,reason,start_utc,end_utc,samples,p95_ms,err_pct,s5xx_pct,achieved_rps,target_rps,abort_at_s,limit_rps,notes" > "$ROUNDS_CSV"
csv_status() { "$PY" - "$(npath "$ROUNDS_CSV")" "$1" <<'EOF'
import csv, sys
st = ""
with open(sys.argv[1], encoding="utf-8", newline="") as f:
    for r in csv.DictReader(f):
        if r["round"] == sys.argv[2]: st = r["status"]
print(st)
EOF
}

mem_fail=0
mem_check() {   # 가장 작은 MemAvailable(MiB)을 출력. 읽지 못하면 빈 값
  local n v min=""
  for n in $APP_NODES; do
    v=$(timeout 25 bash -c ". $(q "$NODE_BIN/lib.sh"); on_node $n \"awk '/^MemAvailable:/ { print int(\\\$2 / 1024) }' /proc/meminfo\"" 2> /dev/null || true)
    [[ $v =~ ^[0-9]+$ ]] || { echo ""; return; }
    if [ -z "$min" ] || [ "$v" -lt "$min" ]; then min=$v; fi
  done
  echo "$min"
}

stop_jmeter() {
  local jbin
  jbin=$(dirname "$(command -v "${JMETER:-jmeter}" 2> /dev/null || echo .)")
  if [ -x "$jbin/stoptest.sh" ]; then "$jbin/stoptest.sh" > /dev/null 2>&1 || true
  elif [ -f "$jbin/stoptest.cmd" ]; then cmd //c "$(npath "$jbin/stoptest.cmd")" > /dev/null 2>&1 || true
  else printf 'StopTestNow' > /dev/udp/127.0.0.1/4445 2> /dev/null || true; fi
}

# 회차 하나 — 끝나면 CSV 한 줄
run_round() {
  local r=$1 name ph tg prof req extra notes warm kind rd jtl abortf donef mon_pid run_pid st reason
  name=$(field "$r" 0) ph=$(field "$r" 1) tg=$(field "$r" 2) prof=$(field "$r" 3) req=$(field "$r" 4)
  extra=$(field "$r" 5) notes=$(field "$r" 6) warm=$(field "$r" 7) kind=$(field "$r" 8)
  rd=$(round_dir "$name")
  local done_st; done_st=$(csv_status "$name")
  # skip 은 끝난 것으로 보지 않는다 — 선행을 다시 돌려 합격하면 다음 호출에서 돈다
  if [ "$REDO" = 0 ] && [ -n "$done_st" ] && [ "$done_st" != skip ]; then log "[$name] CSV 에 있다($done_st) — 건너뜀"; return 0; fi
  if [ -e "$rd" ]; then local cut; cut="$rd.cut-$(date +%Y%m%d%H%M%S)"; mv "$rd" "$cut"; warn "[$name] 끝나지 않은 결과를 옮겼다 → $cut"; fi
  for q2 in $req; do
    if [ "$(csv_status "$q2")" != pass ]; then
      log "[$name] 건너뜀 — 선행 $q2 가 pass 아님($(csv_status "$q2"))"
      printf '%s,%s,%s,"%s",skip,"선행 %s 불합격",,,,,,,,,,,"%s"\n' "$name" "$ph" "$tg" "$prof" "$q2" "$notes" >> "$ROUNDS_CSV"
      return 0
    fi
  done
  if [ "$MEASURE" != none ]; then
    local m; m=$(mem_check)
    [ -n "$m" ] || die "[$name] 앱 노드 메모리를 읽지 못했다 — 목록을 멈춘다(SSH · 노드 상태 확인)"
    [ "$m" -ge "$MEM_MIN_MB" ] || die "[$name] 시작 전 MemAvailable ${m} MiB < ${MEM_MIN_MB} — 목록을 멈춘다. 슬롯 재시작(운영 작업) 뒤 다시 부르면 여기서 이어진다"
    bash "$NODE_BIN/pre-round.sh" "$name" || warn "[$name] pre-round 경고/실패 — 계속"
    bash "$NODE_BIN/record.sh" start "$name"
  fi
  mkdir -p "$rd"
  local logf=$rd/rounds-1m.log start_utc end_utc
  abortf=$rd/.abort donef=$rd/.done jtl=$rd/jmeter/result.jtl
  rm -f "$abortf" "$donef"
  start_utc=$(date -u +%FT%TZ)
  log "[$name] 시작 — $tg $prof ${extra:+($extra)} · $notes"
  # 타이머(VariableThroughputTimer)는 목표와 무관하게 초당 약 1건을 덜 보낸다 — 10/4 R04(10 → 9.0 · 25 → 24.4 · 40 → 39.2)와
  # 10/5 확인(상세 9 → 7.94 · 알림 6 → 5.14). 그래서 JMeter 에 넘기는 속도에만 TST_OFFSET 을 더한다. 판정의 목표는 표 값 그대로다 —
  # run.sh 에 RATE_OFFSET 으로 알려 steps.json · round.json 의 목표도 표 값으로 남긴다(분석 · 결과서가 읽는다)
  local offered
  offered=$("$PY" -c 'import re,sys
o=float(sys.argv[2])
def f(m):
    a=m.group(2).split(","); k={"const":1,"line":2,"step":2}[m.group(1)]
    for i in range(k): a[i]=("%g" % (float(a[i])+o))
    return m.group(1)+"("+",".join(a)+")"
print(re.sub(r"(const|line|step)\(([^)]*)\)", f, sys.argv[1]))' "$prof" "${TST_OFFSET:-1}")
  log "[$name] JMeter 에 넘기는 속도 — $offered (타이머 보정 +${TST_OFFSET:-1})"
  # shellcheck disable=SC2086
  RATE_OFFSET=${TST_OFFSET:-1} KIND=$([ "$tg" = mix ] && echo mix || echo solo) WARMUP_SEC=$warm NOTES="$notes" SCENARIO="$name" PLAN="$PLAN_FILE" \
    bash "$HERE/run.sh" "$name" - "$offered" -- -Jtarget="$tg" $extra >> "$logf" 2>&1 &
  run_pid=$!
  # 창 감시 — 10초 창 둘 연속이면 중단 파일을 쓴다
  "$PY" - "$(npath "$jtl")" "$(npath "$abortf")" "$(npath "$donef")" "$P95_MS" "$ERR5XX_PCT" "$MIN_WIN" <<'EOF' >> "$logf" 2>&1 &
import csv, io, math, os, sys, time
jtl, abortf, donef = sys.argv[1:4]
p95_lim, e5_lim, min_win = float(sys.argv[4]), float(sys.argv[5]), int(sys.argv[6])
W = 10
off, head, buf, wins, done_b = 0, None, "", {}, -1
streak = {"p95": 0, "5xx": 0}
# 회차 시작 직후(계정 로그인 몰림 · 캐시 데우기)는 판정에서 뺀다 — ABORT_GRACE_S(기본 0). 지난 보고서의 워밍업 제외와 같은 취지
grace, first_end = float(os.environ.get("ABORT_GRACE_S", "0")), None
while not os.path.exists(donef):
    time.sleep(2)
    if os.path.exists(jtl):
        with open(jtl, "rb") as f:
            f.seek(off); data = f.read(); off += len(data)
        buf += data.decode("utf-8", "replace")
        *lines, buf = buf.split("\n")
        for ln in lines:
            if not ln.strip(): continue
            if head is None: head = next(csv.reader([ln])); continue
            r = dict(zip(head, next(csv.reader([ln]))))
            if (r.get("label") or "").endswith(" setup"): continue
            try: end = (float(r["timeStamp"]) + float(r["elapsed"])) / 1000.0
            except (KeyError, ValueError): continue
            if first_end is None: first_end = end
            w = wins.setdefault(int(end // W), [[], 0])
            w[0].append(float(r["elapsed"]))
            if (r.get("responseCode") or "")[:1] == "5": w[1] += 1
    now = time.time()
    for b in sorted(k for k in wins if k > done_b and (k + 1) * W + 3 < now):
        el, n5 = wins.pop(b); done_b = b
        if len(el) < min_win: continue
        if first_end is not None and (b + 1) * W <= first_end + grace:
            print(f"window {time.strftime('%H:%M:%S', time.localtime((b + 1) * W))} n={len(el)} (준비 구간 {grace:.0f}초 — 판정 제외)", flush=True); continue
        el.sort(); p95 = el[min(len(el) - 1, math.ceil(0.95 * len(el)) - 1)]; e5 = 100.0 * n5 / len(el)
        streak["p95"] = streak["p95"] + 1 if p95 > p95_lim else 0
        streak["5xx"] = streak["5xx"] + 1 if e5 > e5_lim else 0
        print(f"window {time.strftime('%H:%M:%S', time.localtime((b + 1) * W))} n={len(el)} p95={p95:.0f}ms 5xx={e5:.2f}%", flush=True)
        for k, v in streak.items():
            if v >= 2 and not os.path.exists(abortf):
                with open(abortf, "w", encoding="utf-8") as f: f.write(f"{k} 두 창 연속(p95 {p95:.0f} ms · 5xx {e5:.2f} %)\n{(b + 1) * W}\n")
EOF
  mon_pid=$!
  local last_mem=0 aborted=""
  while kill -0 "$run_pid" 2> /dev/null; do
    sleep 2
    if [ -z "$aborted" ] && [ -s "$abortf" ]; then aborted=$(head -1 "$abortf"); warn "[$name] 중단 — $aborted"; stop_jmeter; fi
    if [ -z "$aborted" ] && [ "$MEASURE" != none ] && [ $(($(date +%s) - last_mem)) -ge "$MEM_EVERY" ]; then
      last_mem=$(date +%s)
      local m; m=$(mem_check)
      if [ -z "$m" ]; then
        mem_fail=$((mem_fail + 1))
        if [ $mem_fail -ge 3 ]; then printf '메모리를 세 번 연속 읽지 못함\n%s\n' "$(date +%s)" > "$abortf"; fi
      else
        mem_fail=0
        [ "$m" -ge "$MEM_MIN_MB" ] || printf 'MemAvailable %s MiB < %s\n%s\n' "$m" "$MEM_MIN_MB" "$(date +%s)" > "$abortf"
      fi
    fi
  done
  wait "$run_pid" || warn "[$name] run.sh 가 0 이 아닌 값으로 끝났다 — $logf"
  touch "$donef"; wait "$mon_pid" 2> /dev/null || true
  end_utc=$(date -u +%FT%TZ)
  if [ "$MEASURE" != none ]; then bash "$NODE_BIN/record.sh" stop "$name" || warn "[$name] record stop 실패"; fi

  # 판정 → CSV
  "$PY" - "$(npath "$jtl")" "$(npath "$rd/steps.json")" "$(npath "$abortf")" "$(npath "$ROUNDS_CSV")" \
      "$name" "$ph" "$tg" "$prof" "$start_utc" "$end_utc" "$notes" "$P95_MS" "$kind" <<'EOF'
import csv, json, math, os, sys
jtl, stepsf, abortf, out, name, ph, tg, prof, su, eu, notes, p95lim, kind = sys.argv[1:14]
p95lim = float(p95lim)
rows = []
if os.path.exists(jtl):
    with open(jtl, encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.DictReader(f):
            if (r.get("label") or "").endswith(" setup"): continue
            try: rows.append((float(r["timeStamp"]) / 1000, float(r["elapsed"]), r.get("responseCode") or "", (r.get("success") or "").lower() == "true"))
            except (KeyError, ValueError): pass
steps = json.load(open(stepsf, encoding="utf-8")) if os.path.exists(stepsf) else {"start_epoch_ms": 0, "steps": []}
t0 = steps["start_epoch_ms"] / 1000
# steps.json 의 속도는 JMeter 에 넘긴 값(타이머 보정 +TST_OFFSET)이다 — 판정 · 한계는 보정을 뺀 목표로 센다
_off = float(os.environ.get("TST_OFFSET", "1"))
for s in steps["steps"]:
    s["target_rps"] = max(0.0, s["target_rps"] - _off)
    if "ramp_from_rps" in s: s["ramp_from_rps"] = max(0.0, s["ramp_from_rps"] - _off)
n = len(rows)
el = sorted(r[1] for r in rows)
p95 = el[min(n - 1, math.ceil(0.95 * n) - 1)] if n else None
err = 100.0 * sum(1 for r in rows if not r[3]) / n if n else None
s5 = 100.0 * sum(1 for r in rows if r[2][:1] == "5") / n if n else None
n429 = sum(1 for r in rows if r[2] == "429")
# 처리량 — 고정 단계(ramp_from_rps 없음)마다 실제 ÷ 계획, 가장 낮은 값
ach, tgt, ratio = None, None, None
for s in steps["steps"]:
    if "ramp_from_rps" in s: continue
    a, b = t0 + s["from_s"], t0 + s["to_s"]
    if b <= a: continue
    got = sum(1 for r in rows if a <= r[0] < b) / (b - a)
    rr = got / s["target_rps"] if s["target_rps"] else None
    if rr is not None and (ratio is None or rr < ratio): ratio, ach, tgt = rr, got, s["target_rps"]
aborted, abort_at, limit = "", None, None
if os.path.exists(abortf) and os.path.getsize(abortf):
    lines = open(abortf, encoding="utf-8").read().splitlines()
    aborted = lines[0]
    try: abort_at = float(lines[1]) - t0
    except (IndexError, ValueError): pass
    if abort_at is not None:
        for s in steps["steps"]:
            if s["from_s"] <= abort_at < s["to_s"]:
                a = s.get("ramp_from_rps", s["target_rps"])
                limit = a + (s["target_rps"] - a) * (abort_at - s["from_s"]) / (s["to_s"] - s["from_s"])
growth = ph == "stress" and (prof.strip().startswith("step(") or (prof.strip().startswith("line(") and "const(" not in prof))
why = []
if name == "S-429":
    status = "pass" if n429 > 0 and (s5 or 0) <= 1 else "fail"
    why.append(f"429 {n429}건")
elif aborted:
    status = "limit" if growth else "fail"; why.append("중단: " + aborted)
elif n == 0:
    status = "fail"; why.append("표본 없음")
else:
    if p95 > p95lim: why.append(f"p95 {p95:.0f} ms > {p95lim:.0f}")
    if err > 1: why.append(f"오류 {err:.2f} %")
    if ratio is not None and ratio < 0.95: why.append(f"처리량 {100 * ratio:.1f} %")
    status = "fail" if why else "pass"
f = lambda v, d=1: "" if v is None else f"{v:.{d}f}"
with open(out, "a", encoding="utf-8", newline="") as fo:
    csv.writer(fo).writerow([name, ph, tg, prof, status, "; ".join(why), su, eu, n, f(p95, 0), f(err, 2), f(s5, 2),
                             f(ach, 2), f(tgt, 1), f(abort_at, 0), f(limit, 1), notes])
print(f"[{name}] {status} — 표본 {n} · p95 {f(p95, 0)} ms · 오류 {f(err, 2)} % · 5xx {f(s5, 2)} %"
      + (f" · 처리량 {100 * ratio:.1f} %" if ratio is not None and not why else "") + (f" · 한계 {limit:.1f} RPS" if limit is not None else "")
      + (" · " + "; ".join(why) if why else ""))
EOF
  if [ "$MEASURE" != none ]; then
    sleep 10
    bash "$NODE_BIN/collect.sh" "$name" >> "$logf" 2>&1 || warn "[$name] collect 실패 — 나중에 bash node/collect.sh $name"
  fi
  rm -f "$donef"
}

log "회차 $((${#SEL[@]}))개 · 결과 $ROUNDS_CSV · MEASURE=$MEASURE"
MODE=quiet estimate "${SEL[@]}"
first=1
for r in "${SEL[@]}"; do
  if [ $first = 0 ]; then
    left=$((GAP_S - ($(date +%s) - round_end)))
    [ $left -le 0 ] || sleep $left
  fi
  first=0
  run_round "$r"
  round_end=$(date +%s)
done
log "끝 — $ROUNDS_CSV"
