#!/usr/bin/env bash
# 알림 상태 이력 — 회차 구간에 Grafana Cloud 관리형 알림 규칙이 어떻게 움직였나를 받아 남긴다(INF-06 #390).
#
#   read -rs GRAFANA_SA_TOKEN && export GRAFANA_SA_TOKEN      토큰은 화면에 치지 않고 받는다(infra/grafana/apply.sh 와 같다)
#   GRAFANA_URL=https://<스택>.grafana.net bash alerts.sh <회차>
#
# 읽기만 한다 — 규칙 · 대시보드 · 무음을 바꾸지 않는다. 운영 작업이 아니다. 토큰은 스택의 서비스 계정 토큰(apply.sh 의 Editor 토큰이면
# 읽기 권한이 있다 — 상태 이력 읽기 권한은 예비 회차(확인 ④)에서 실제 응답으로 확인한다). 저장소 · 출력에 남기지 않는다 — curl 에는
# 권한 600 의 임시 파일로 헤더를 넘긴다. GRAFANA_URL 이 없으면 apply.sh 의 GRAFANA_STACK_URL 을 쓴다.
#
# 구간 — results/<회차>/meta.json 의 start_utc ~ end_utc + 꼬리 ALERT_TAIL_MIN 분(기본 10).
#   꼬리 10분의 근거: 관측 설계서 5.1 — 평가 1분 · 대기(for) 5분이고, 노드가 통째로 멈춰 시계열이 사라지면 알림까지 약 10분(실측).
#   회차가 끝난 뒤에 Firing 된 것도 이 회차 때문일 수 있어 함께 받고, 집계(analyze/alerts.py)가 「회차 끝난 뒤」로 표시한다.
#
# 받는 것(응답을 손대지 않고 alerts.json 한 봉투에 담는다 — 해석은 analyze/alerts.py)
#   history       GET /api/v1/rules/history?from=<초>&to=<초>&limit=<n>
#                 Grafana 관리형 알림의 상태 이력(state history) API — Grafana 화면의 규칙 「State history」가 부르는 것.
#                 Grafana Cloud 는 상태 이력을 Loki 에 둔다(이 스택의 데이터 소스 `…-alert-state-history` — 관측 설계서 4.2).
#                 응답은 데이터 프레임 JSON(time · line · labels), line 하나가 상태 전환 하나(previous · current · ruleUID · ruleTitle).
#                 근거: Grafana OpenAPI 문서(#89795)로 확인 — 경로 /api/v1/rules/history, from · to 는 유닉스 초.
#                 응답 프레임의 칸 이름(time · line · labels)은 예비 회차(확인 ④)에서 실제 응답으로 본다 — 다르면 alerts.py 를 고친다
#   annotations   GET /api/annotations?type=alert&from=<ms>&to=<ms>&limit=<n> — 공식 HTTP API(Annotations)의 알림 주석.
#                 상태 이력 백엔드가 주석일 때의 원천. history 가 비거나 실패할 때만 집계가 쓴다
#   rules         GET /api/v1/provisioning/alert-rules — 공식 Alerting provisioning HTTP API. 지금 걸린 규칙 목록(제목 · UID).
#                 집계가 저장소 정의(infra/grafana/alerting/rules-*.json, 관측 설계서 5.1 의 열셋)와 대조한다
# 실패한 요청은 그 칸을 null 로 두고 <칸>_error 에 HTTP 코드만 남긴다(응답 본문에 비밀이 없어도 남기지 않는다).
#
#   ALERT_TAIL_MIN   꼬리(분, 기본 10)
#   ALERT_LIMIT      요청마다 최대 건수(기본 5000)
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() { echo "사용법: GRAFANA_URL=https://<스택>.grafana.net bash alerts.sh <회차>  (GRAFANA_SA_TOKEN 환경 변수)" >&2; exit 2; }
ROUND=${1:-}; [ -n "$ROUND" ] || usage
check_round "$ROUND"
GRAFANA_URL=${GRAFANA_URL:-${GRAFANA_STACK_URL:-}}
[ -n "$GRAFANA_URL" ] || die "GRAFANA_URL 이 필요하다 — 예: https://<스택>.grafana.net"
[ -n "${GRAFANA_SA_TOKEN:-}" ] || die "GRAFANA_SA_TOKEN 이 필요하다 — read -rs GRAFANA_SA_TOKEN && export GRAFANA_SA_TOKEN"
TAIL_MIN=${ALERT_TAIL_MIN:-10}
LIMIT=${ALERT_LIMIT:-5000}
[[ $TAIL_MIN =~ ^[0-9]+$ ]] || die "ALERT_TAIL_MIN 은 분(정수)"
[[ $LIMIT =~ ^[0-9]+$ ]] || die "ALERT_LIMIT 은 정수"
BASE=${GRAFANA_URL%/}

RD=$(round_dir "$ROUND")
[ -f "$RD/meta.json" ] || die "meta.json 이 없다: $RD — record.sh start · stop 을 먼저 한다"
START=$(sed -n 's/.*"start_utc": "\([^"]*\)".*/\1/p' "$RD/meta.json")
END=$(sed -n 's/.*"end_utc": "\([^"]*\)".*/\1/p' "$RD/meta.json")
[ -n "$START" ] && [ -n "$END" ] || die "meta.json 에 start_utc · end_utc 가 없다 — record.sh stop $ROUND 을 먼저 한다"

PY=
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import json' >/dev/null 2>&1; then PY=$c; break; fi
done
[ -n "$PY" ] || die "python 을 찾지 못했다 — 시각 변환 · JSON 묶기에 필요하다"
export PYTHONIOENCODING=utf-8
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# 시각 — ISO(UTC) → 유닉스 밀리초. GNU date 의 밀리초 표기 차이를 피해 python 으로
read -r START_MS END_MS <<< "$("$PY" -c '
import sys
from datetime import datetime
f = lambda s: int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
print(f(sys.argv[1]), f(sys.argv[2]))' "$START" "$END" | tr -d '\r')"
FROM_MS=$START_MS
TO_MS=$((END_MS + TAIL_MIN * 60000))
FROM_S=$((FROM_MS / 1000)); TO_S=$(((TO_MS + 999) / 1000))

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
AUTH=$WORK/auth
( umask 077; printf 'Authorization: Bearer %s\n' "$GRAFANA_SA_TOKEN" > "$AUTH" )
AUTH_FILE=$(winpath "$AUTH")

# get <이름> <경로> — 본문은 $WORK/<이름>.json, HTTP 코드는 $WORK/<이름>.code
get() {
  local code
  code=$(curl -sS -o "$(winpath "$WORK/$1.json")" -w '%{http_code}' -H @"$AUTH_FILE" -H 'Accept: application/json' "$BASE$2" 2> "$WORK/$1.curlerr" || true)
  printf '%s' "${code:-000}" > "$WORK/$1.code"
  log "  $1 — HTTP ${code:-000}"
}

log "[Grafana] 알림 상태 이력 — $START ~ $END + ${TAIL_MIN}분"
get history "/api/v1/rules/history?from=$FROM_S&to=$TO_S&limit=$LIMIT"
get annotations "/api/annotations?type=alert&from=$FROM_MS&to=$TO_MS&limit=$LIMIT"
get rules "/api/v1/provisioning/alert-rules"

# 한 봉투로 — 2xx 이고 JSON 이면 그대로, 아니면 null 과 「HTTP <코드>」
"$PY" - "$(winpath "$WORK")" "$(winpath "$RD/alerts.json")" "$FROM_MS" "$TO_MS" "$START_MS" "$END_MS" <<'PY'
import json, sys
from datetime import datetime, timezone
from pathlib import Path
w, out = Path(sys.argv[1]), Path(sys.argv[2])
fr, to, rs, re_ = (int(x) for x in sys.argv[3:7])
env = {"captured_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
       "from_ms": fr, "to_ms": to, "round_start_ms": rs, "round_end_ms": re_}
for k in ("history", "annotations", "rules"):
    code = (w / f"{k}.code").read_text().strip() if (w / f"{k}.code").exists() else "000"
    body, err = None, None
    if code.startswith("2"):
        try:
            body = json.loads((w / f"{k}.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            err = f"HTTP {code} — JSON 아님"
    else:
        err = f"HTTP {code}"
    env[k], env[k + "_error"] = body, err
out.write_text(json.dumps(env, ensure_ascii=False), encoding="utf-8")
print(f"alerts.json — " + " · ".join(f"{k} {'ok' if env[k] is not None else env[k + '_error']}" for k in ("history", "annotations", "rules")))
PY
log "$(fsize "$RD/alerts.json") B  $RD/alerts.json"
