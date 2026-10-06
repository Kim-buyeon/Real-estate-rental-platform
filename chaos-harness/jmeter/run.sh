#!/usr/bin/env bash
# 로그인 부하 회차 하나를 JMeter 로 돌린다(INF-06 #390). 측정 README 「회차마다」의 3번 자리다 — record.sh start 와 stop 사이.
#
#   bash run.sh <회차> <mode> <load_profile> [bg_rps] [-- <JMeter 인자> …]
#
#   mode           valid | wrong | enum                      로그인 요청의 종류(login.jmx 머리 주석)
#                  -                                         mode 를 넘기지 않는다 — 로그인 플랜이 아닌 플랜(PLAN)용
#   load_profile   const(N,T) line(N,K,T) step(N,K,S,T) …     로그인 초당 요청 수 계단 — Throughput Shaping Timer 문법(N · K · S 정수)
#                  burst:<계정 수>,<초>                        몰림 — 그 초 안에 계정마다 한 번 로그인(mode 는 valid 만)
#   bg_rps         0(기본) | 정수                              > 0 이면 일반 요청(지도 2단계)을 같은 길이 동안 고정 초당 요청 수로 같이 보낸다
#   -- 뒤          JMeter 에 그대로 붙인다(예: -- -Jtarget=map-clusters). 플랜마다 다른 -J 속성은 여기로 준다.
#                  bg_rps 를 건너뛰려면 -- 를 바로 붙인다(bg_rps 0)
#
# 플랜이 받는 -J — run.sh 가 늘 넘기는 것: host · port · protocol · accounts · hold_s · max_threads · bg_max_threads,
#   계단이면 load_profile, 몰림이면 burst, SIGNUP=true 면 signup · signup_accounts, bg_rps > 0 이면 bg_rps · bg_tst.load_profile,
#   mode 가 - 가 아니면 mode. 다른 플랜(PLAN)은 쓰지 않는 속성을 무시하면 된다.
#
# 남기는 것 — results/<회차>/ 아래
#   jmeter/result.jtl · jmeter/html/(대시보드 -e -o) · jmeter/jmeter.log
#   steps.json     단계 경계. 다른 스크립트(analyze steps.py)가 읽는 계약 —
#                  {"start_epoch_ms": <ms>, "profile": "<load_profile>", "steps": [{"index": 1, "target_rps": 10.0, "from_s": 0, "to_s": 180}, …]}
#                  start_epoch_ms 는 실행 전에 JMeter 를 띄우기 직전 시각으로 쓰고, 끝난 뒤 첫 표본의 시각으로 고친다
#                  (레이블이 「 setup」으로 끝나는 준비 요청 — 가입 · 토큰 풀 setUp — 은 뺀다) —
#                  타이머의 계단은 JMeter 기동(JVM · 가입 setUp)이 아니라 첫 표본에서 시작한다(VariableThroughputTimer 의 startSec).
#                  고친 근거는 start_basis, 띄운 시각은 launch_epoch_ms. line 단계는 target_rps = 끝 값, ramp_from_rps = 시작 값
#   round.json     회차 조건(analyze README 의 키). 이미 있으면 합친다 — 여기서 주는 값만 덮고 나머지는 그대로 둔다
#
# 바꿀 수 있는 값(환경 변수)
#   PLAN           플랜 파일(기본 같은 폴더 login.jmx). round.json 의 plan 칸에 저장소 기준 경로로 남는다
#   JMETER         jmeter 실행 파일(기본 PATH 의 jmeter. Windows 는 jmeter.bat 경로)
#   HEAP           JMeter JVM 힙(기본 "-Xms1g -Xmx1g -XX:MaxMetaspaceSize=256m" — jmeter 실행 스크립트가 읽는 변수)
#   HOST · PORT · PROTOCOL   대상(기본 localhost · 443 · https). 운영 회차는 HOST=<APP-01 사설 IP>
#   ACCOUNTS_DIR   모드별 계정 CSV 자리(기본 C:/Users/bu200/loadtest-data/secret/jmeter — make-accounts.sh 의 출력)
#   ACCOUNTS       계정 CSV 를 직접 준다(기본 $ACCOUNTS_DIR/<mode>.csv, mode 가 - 거나 burst 면 valid.csv)
#   SIGNUP=true    회차 앞에 $ACCOUNTS_DIR/signup.csv 로 가입(setUp). 이미 가입된 계정은 409 로 지나간다
#   BG_PATH        일반 요청 경로(기본 login.jmx 의 지도 묶음 예시)
#   MAX_THREADS · BG_MAX_THREADS   스레드 상한(기본 300 · 50)
#   round.json 칸 — SCENARIO · KIND(mix · solo · jitter, 기본 bg_rps > 0 이면 mix 아니면 solo) · TRACING_RATIO · PROFILER ·
#                   AUTO_EXPLAIN_MS · WARMUP_SEC(round.json 에 없을 때 기본 60 — #390 승인 계획 [11.2]) · NOTES · JMETER_VERSION(기본 5.6.3)
#   RATE_OFFSET    load_profile 에 이미 더해 넘긴 타이머 보정(rounds-1m.sh 의 TST_OFFSET). steps.json · round.json 의 목표에서 뺀다
#   RESULTS_DIR    결과 자리(기본 chaos-harness/measure/results — 측정 스크립트와 같다)
#
# 결과가 이미 있으면(jmeter/result.jtl) 덮지 않고 멈춘다. 계정 CSV 의 비밀번호는 출력하지 않는다.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=../measure/node/lib.sh
. "$HERE/../measure/node/lib.sh"

usage() { echo "사용법: bash run.sh <회차> <valid|wrong|enum|-> <load_profile | burst:<계정 수>,<초>> [bg_rps] [-- <JMeter 인자> …]" >&2; exit 2; }
[ $# -ge 3 ] || usage
ROUND=$1 MODE=$2 PROFILE=$3
shift 3
BG_RPS=0
if [ $# -gt 0 ] && [ "$1" != -- ]; then BG_RPS=$1; shift; fi
EXTRA=()
if [ $# -gt 0 ]; then [ "$1" = -- ] || usage; shift; EXTRA=("$@"); fi
check_round "$ROUND"
case $MODE in valid|wrong|enum|-) ;; *) usage ;; esac
PLAN=${PLAN:-$HERE/login.jmx}
[ -r "$PLAN" ] || die "플랜이 없다: $PLAN"
# round.json 의 plan 칸 — 저장소 기준 경로(저장소 밖이면 절대 경로 그대로)
PLAN_ABS=$(cd "$(dirname "$PLAN")" && pwd)/$(basename "$PLAN")
PLAN_REL=${PLAN_ABS#"$(cd "$HERE/../.." && pwd)/"}
[[ $BG_RPS =~ ^[0-9]+$ ]] || die "bg_rps 는 0 이상의 정수다(타이머가 정수로 읽는다): $BG_RPS"

BURST=""
if [[ $PROFILE =~ ^burst:([0-9]+),([0-9]+)$ ]]; then
  BURST="${BASH_REMATCH[1]},${BASH_REMATCH[2]}"
  [ "$MODE" = valid ] || [ "$MODE" = - ] || die "burst 는 mode valid(또는 -)로만 돈다"
  [ "${BASH_REMATCH[1]}" -gt 0 ] && [ "${BASH_REMATCH[2]}" -gt 0 ] || die "burst 의 계정 수 · 초는 1 이상"
fi

# 실제로 도는 것을 고른다 — Windows 의 python3 는 스토어 바로가기라 이름만 있고 돌지 않을 수 있다
PY=""
for c in python3 python; do
  if command -v "$c" > /dev/null 2>&1 && "$c" -c 'import json' > /dev/null 2>&1; then PY=$c; break; fi
done
# 메시지는 한국어다 — Windows 콘솔 기본 인코딩(cp949)으로 내면 깨진다
export PYTHONIOENCODING=utf-8
[ -n "$PY" ] || die "python3(또는 python)이 없다 — 계단 해석 · JSON 쓰기에 쓴다"
JMETER=${JMETER:-jmeter}
command -v "$JMETER" > /dev/null || die "jmeter 를 찾지 못했다($JMETER) — 설치는 같은 폴더 README, 경로는 JMETER 로"
export HEAP=${HEAP:--Xms1g -Xmx1g -XX:MaxMetaspaceSize=256m}

# Windows 의 JMeter · Python 에는 C:/… 꼴로 넘긴다(Git Bash). Linux 는 그대로
npath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

ACCOUNTS_DIR=${ACCOUNTS_DIR:-C:/Users/bu200/loadtest-data/secret/jmeter}
if [ -n "$BURST" ] || [ "$MODE" = - ]; then def_csv=valid.csv; else def_csv=$MODE.csv; fi
ACCOUNTS=${ACCOUNTS:-$ACCOUNTS_DIR/$def_csv}
[ -r "$ACCOUNTS" ] || die "계정 CSV 가 없다: $ACCOUNTS — make-accounts.sh 로 만든다"
SIGNUP_CSV=""
if [ "${SIGNUP:-}" = true ]; then
  SIGNUP_CSV=$ACCOUNTS_DIR/signup.csv
  [ -r "$SIGNUP_CSV" ] || die "가입 CSV 가 없다: $SIGNUP_CSV"
fi

RDIR=$(round_dir "$ROUND")
JDIR=$RDIR/jmeter
[ ! -e "$JDIR/result.jtl" ] || die "이미 결과가 있다: $JDIR/result.jtl — 회차 이름을 바꾸거나 그 폴더를 치운다"
[ ! -e "$JDIR/html" ] || die "대시보드 폴더가 이미 있다: $JDIR/html(-e -o 는 빈 폴더만 쓴다)"
mkdir -p "$JDIR"

# ── 계단 해석 · steps.json · round.json ─────────────────────────────────────────────────────────────────
# 해석 규칙은 플러그인과 같다(VariableThroughputTimer.parseChunk · JMeterPluginsUtils.getSecondsForShortString):
# ')' 로 나눈 조각마다 '(' · ',' 로 나눠 const · line · step, 초당 요청 수는 정수, 길이는 숫자(초) 또는 d · h · m · s 조합.
# step(N,K,S,T) 는 N 부터 K 까지(K 포함) S 씩, 단계마다 T.
LAUNCH_MS=$("$PY" -c 'import time; print(int(time.time() * 1000))')
CMD_LINE="PLAN=$PLAN_REL bash chaos-harness/jmeter/run.sh $ROUND $MODE '$PROFILE' $BG_RPS${EXTRA[*]:+ -- ${EXTRA[*]}}"
TOTAL_S=$(
  ROUND_JSON=$(npath "$RDIR/round.json") STEPS_JSON=$(npath "$RDIR/steps.json") PROFILE="$PROFILE" BURST="$BURST" \
  LAUNCH_MS="$LAUNCH_MS" BG_RPS="$BG_RPS" CMD_LINE="$CMD_LINE" PLAN_REL="$PLAN_REL" "$PY" - <<'EOF'
import json, os, re, sys

def seconds(s):
    s = s.strip()
    total, cur = 0, ""
    for c in s:
        if c.isdigit():
            cur += c
            continue
        mul = {"s": 1, "m": 60, "h": 3600, "d": 86400}.get(c.lower())
        if mul is None or not cur:
            sys.exit(f"길이를 읽지 못했다: {s!r} (숫자 또는 1h2m3s 꼴)")
        total += int(cur) * mul
        cur = ""
    if cur:
        total += int(cur)
    return total

def parse(profile):
    rows = []   # (from_rps, to_rps, duration)
    for chunk in profile.split(")"):
        if not chunk.strip():
            continue
        parts = re.split(r"[(,]", chunk)
        kind = parts[0].strip().lower()
        try:
            if kind == "const":
                n = int(parts[1].strip()); rows.append((n, n, seconds(parts[2])))
            elif kind == "line":
                rows.append((int(parts[1].strip()), int(parts[2].strip()), seconds(parts[3])))
            elif kind == "step":
                a, b, inc = int(parts[1].strip()), int(parts[2].strip()), int(parts[3].strip())
                if inc <= 0:
                    sys.exit(f"step 의 폭은 1 이상: {chunk})")
                d = seconds(parts[4])
                inc = -inc if a > b else inc
                n = a
                while (n <= b) if inc > 0 else (n > b):
                    rows.append((n, n, d)); n += inc
            else:
                sys.exit(f"모르는 조각: {chunk.strip()}) — const · line · step 만")
        except (IndexError, ValueError):
            sys.exit(f"조각을 읽지 못했다: {chunk.strip()}) — 초당 요청 수는 정수")
    if not rows:
        sys.exit("load_profile 이 비었다")
    if any(r[2] <= 0 for r in rows):
        sys.exit("길이 0 인 조각이 있다")
    if any(min(r[0], r[1]) <= 0 for r in rows):
        # 위키 「Few Important Notes」 — 0 RPS 는 시작 · 도중 모두 피한다
        sys.exit("초당 요청 수 0 이 있다 — 타이머 위키가 피하라고 한 값이다")
    return rows

profile, burst = os.environ["PROFILE"], os.environ["BURST"]
steps, t = [], 0
if burst:
    n, s = (int(x) for x in burst.split(","))
    steps.append({"index": 1, "target_rps": round(n / s, 3), "from_s": 0, "to_s": s})
    t = s
else:
    # RATE_OFFSET — JMeter 에 넘긴 속도에 더한 타이머 보정(rounds-1m.sh). 기록하는 목표는 보정을 뺀 값이다
    off = float(os.environ.get("RATE_OFFSET") or 0)
    for i, (a, b, d) in enumerate(parse(profile), 1):
        st = {"index": i, "target_rps": float(b) - off, "from_s": t, "to_s": t + d}
        if a != b:
            st["ramp_from_rps"] = float(a) - off
        steps.append(st)
        t += d

with open(os.environ["STEPS_JSON"], "w", encoding="utf-8") as f:
    json.dump({"start_epoch_ms": int(os.environ["LAUNCH_MS"]), "launch_epoch_ms": int(os.environ["LAUNCH_MS"]),
               "start_basis": "launch", "profile": profile, "steps": steps}, f, ensure_ascii=False, indent=2)

# round.json — 여기서 주는 값만 덮는다
path = os.environ["ROUND_JSON"]
cur = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        cur = json.load(f)
def num(v):
    try:
        return int(v) if re.fullmatch(r"-?\d+", v) else float(v)
    except ValueError:
        return v
bg = int(os.environ["BG_RPS"])
new = {
    "tool": f"JMeter {os.environ.get('JMETER_VERSION') or '5.6.3'}",
    "plan": os.environ["PLAN_REL"],
    "command": os.environ["CMD_LINE"],
    "kind": os.environ.get("KIND") or cur.get("kind") or ("mix" if bg > 0 else "solo"),
    "target_rps": max(max(s["target_rps"], s.get("ramp_from_rps", 0)) for s in steps) + bg,
}
if os.environ.get("RATE_OFFSET"):
    new["rate_offset"] = num(os.environ["RATE_OFFSET"])
for key, env in (("scenario", "SCENARIO"), ("notes", "NOTES"), ("profiler", "PROFILER")):
    if os.environ.get(env):
        new[key] = os.environ[env]
for key, env in (("tracing_ratio", "TRACING_RATIO"), ("auto_explain_ms", "AUTO_EXPLAIN_MS"), ("warmup_sec", "WARMUP_SEC")):
    if os.environ.get(env):
        new[key] = num(os.environ[env])
if "warmup_sec" not in new and "warmup_sec" not in cur:
    new["warmup_sec"] = 60
cur.update(new)
with open(path, "w", encoding="utf-8") as f:
    json.dump(cur, f, ensure_ascii=False, indent=2)
print(t)
EOF
)
[[ $TOTAL_S =~ ^[0-9]+$ ]] || die "계단을 읽지 못했다"
# Hold 는 계단 총길이 이상(위키 ConcurrencyThreadGroup). 계단은 첫 표본에서, Hold 는 그룹 시작에서 세므로 10초를 더 둔다 —
# 끝은 타이머가 낸다(일정이 끝나면 그 그룹 스레드를 멈춘다)
HOLD_S=$((TOTAL_S + 10))
log "[$ROUND] plan=$PLAN_REL mode=$MODE profile=$PROFILE 총 ${TOTAL_S}초 bg_rps=$BG_RPS → $RDIR"

JARGS=(
  -Jhost="${HOST:-localhost}" -Jport="${PORT:-443}" -Jprotocol="${PROTOCOL:-https}"
  -Jaccounts="$(npath "$ACCOUNTS")" -Jhold_s="$HOLD_S"
  -Jmax_threads="${MAX_THREADS:-300}" -Jbg_max_threads="${BG_MAX_THREADS:-50}"
)
if [ "$MODE" != - ]; then JARGS+=(-Jmode="$MODE"); fi
if [ -n "$BURST" ]; then JARGS+=(-Jburst="$BURST"); else JARGS+=(-Jload_profile="$PROFILE"); fi
if [ -n "$SIGNUP_CSV" ]; then JARGS+=(-Jsignup=true -Jsignup_accounts="$(npath "$SIGNUP_CSV")"); fi
if [ "$BG_RPS" -gt 0 ]; then JARGS+=(-Jbg_rps="$BG_RPS" "-Jbg_tst.load_profile=const($BG_RPS,${TOTAL_S}s)"); fi
if [ -n "${BG_PATH:-}" ]; then JARGS+=(-Jbg_path="$BG_PATH"); fi

# 비밀번호는 명령줄에 없다(CSV 경로만). JMeter 는 결과 화면 없이(-n)
set +e
# -- 뒤 인자는 맨 끝에 붙인다 — 같은 -J 를 다시 주면 뒤의 값이 이긴다
"$JMETER" -n -t "$(npath "$PLAN")" -q "$(npath "$HERE/user.properties")" \
  -l "$(npath "$JDIR/result.jtl")" -j "$(npath "$JDIR/jmeter.log")" -e -o "$(npath "$JDIR/html")" "${JARGS[@]}" "${EXTRA[@]}"
rc=$?
set -e

# ── steps.json 의 시작을 첫 로그인 표본으로 ──────────────────────────────────────────────────────────────
if [ -s "$JDIR/result.jtl" ]; then
  STEPS_JSON=$(npath "$RDIR/steps.json") JTL=$(npath "$JDIR/result.jtl") "$PY" - <<'EOF'
import csv, json, os
first, n, bad = None, 0, 0
with open(os.environ["JTL"], encoding="utf-8", errors="replace", newline="") as f:
    for r in csv.DictReader(f):
        n += 1
        if (r.get("success") or "").lower() != "true":
            bad += 1
        lab = r.get("label") or ""
        if not lab.endswith(" setup"):
            try:
                ts = int(float(r["timeStamp"]))
            except (KeyError, ValueError):
                continue
            first = ts if first is None else min(first, ts)
p = os.environ["STEPS_JSON"]
with open(p, encoding="utf-8") as f:
    sj = json.load(f)
if first is not None:
    sj["start_epoch_ms"], sj["start_basis"] = first, "first sample (setup labels excluded)"
    with open(p, "w", encoding="utf-8") as f:
        json.dump(sj, f, ensure_ascii=False, indent=2)
print(f"표본 {n}건 · 실패 {bad}건 · 계단 시작 {sj['start_basis']}({sj['start_epoch_ms']})")
EOF
fi
[ "$rc" -eq 0 ] || die "JMeter 가 $rc 로 끝났다 — $JDIR/jmeter.log"
log "끝 — $JDIR/result.jtl · $JDIR/html/index.html · $RDIR/steps.json · $RDIR/round.json"
