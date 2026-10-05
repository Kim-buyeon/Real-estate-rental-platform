#!/usr/bin/env bash
# Grafana Cloud 반영 — 대시보드(dashboards/*.json — 운영 요약 · 병목 지도) · 알림 규칙 그룹 · 메일 템플릿 · 연락처를 저장소의 정의대로 올린다(INF-05). 운영 절차서 2장.
#
#   read -rs GRAFANA_SA_TOKEN && export GRAFANA_SA_TOKEN      토큰은 화면에 치지 않고 받는다 — 명령줄에 두면 셸 기록에 남는다
#   GRAFANA_STACK_URL=https://<스택>.grafana.net GRAFANA_ALERT_EMAIL=<받을 주소> bash infra/grafana/apply.sh
#
# 멱등이다 — 같은 정의를 다시 올리면 덮어쓸 뿐 새로 생기지 않는다(템플릿 · 연락처 · 규칙 그룹은 이름 · UID 로 PUT,
# 대시보드는 파일마다 그 파일의 UID — rental-ops-summary · rental-bottleneck — 에 overwrite). 대시보드 판(version)은 내용이 바뀔 때만 오른다(2026-09-28 — 같은 정의를 다시 올려도 판 2 그대로).
#
# 왜 저장소에 두나 — #253 에서 가져온 대시보드가 빈 화면이던 원인 셋이 모두 화면에서만 고친 것이라 저장소가 몰랐다.
# 정의를 여기 두고 이 스크립트로만 올린다. 화면에서 고쳤으면 그 JSON 을 저장소에도 옮긴다 — 규칙 · 템플릿 · 연락처는
# X-Disable-Provenance 로 올려 화면에서도 계속 고칠 수 있게 두었으므로, 다음 실행이 화면의 수정을 덮는다.
#
# 노드가 아니라 운영자 PC 에서 돌린다. 노드의 수집 토큰은 쓰기(remote_write) 전용이라 규칙 · 대시보드를 만들 수 없다(#243).
# 토큰은 스택의 서비스 계정(역할 Editor) 토큰이다. 저장소 · 출력에 남기지 않는다 — 환경 변수로만 받고, curl 에는
# 권한 600 의 임시 파일로 헤더를 넘긴다(명령줄에 두면 프로세스 목록에 보인다). set -x 를 쓰지 않는다.
# 메일 주소도 저장소에 두지 않는다 — contact-point-email.json 의 ${GRAFANA_ALERT_EMAIL} 자리를 실행할 때 채운다.
#
# 이 스택 전용 값 — 규칙 · 대시보드의 폴더 UID fq8pjr(rental 폴더), 연락처 UID efzdy1y03n8xsb, 데이터 소스 UID grafanacloud-prom.
# 스택을 새로 만들면 셋 모두 새 값이라 JSON 과 이 스크립트를 함께 고친다. 로그(Loki) · 추적(Tempo) 데이터 소스는 UID 를 박지 않는다 —
# 병목 지도가 대시보드 변수(유형 loki · tempo)로 고른다. 스택에 하나씩이라 첫 항목이 맞다.
#
# JSON 인코딩은 python 으로 한다(Rocky · 이 PC 모두 있다. jq 는 이 PC 에 없다). Windows 의 python3 는 스토어 안내 껍데기일 수 있어
# 실제로 도는 쪽을 고른다. 경로는 이 스크립트 폴더 기준 상대 경로로 넘긴다 — Windows 의 python 은 /c/... 경로를 읽지 못한다.
set -euo pipefail
: "${GRAFANA_STACK_URL:?GRAFANA_STACK_URL 이 필요하다 — 예: https://<스택>.grafana.net}"
: "${GRAFANA_SA_TOKEN:?GRAFANA_SA_TOKEN 이 필요하다 — 서비스 계정(Editor) 토큰}"
: "${GRAFANA_ALERT_EMAIL:?GRAFANA_ALERT_EMAIL 이 필요하다 — 경보 메일을 받을 주소}"

FOLDER_UID=fq8pjr
CONTACT_UID=efzdy1y03n8xsb
BASE=${GRAFANA_STACK_URL%/}

PY=
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import json' >/dev/null 2>&1; then PY=$c; break; fi
done
[ -n "$PY" ] || { echo "python 을 찾지 못했다 — JSON 인코딩에 필요하다" >&2; exit 1; }
# Windows 의 python 은 콘솔 코드 페이지로 출력한다 — 한국어 오류 문구가 깨지거나 인코딩 오류로 멈추지 않게 UTF-8 로 고정한다
export PYTHONIOENCODING=utf-8

cd "$(dirname "$0")"

AUTH=$(mktemp)
trap 'rm -f "$AUTH"' EXIT
chmod 600 "$AUTH"
printf 'Authorization: Bearer %s\n' "$GRAFANA_SA_TOKEN" > "$AUTH"
# Git Bash 의 curl 은 Windows 프로그램이라 /tmp/... 를 읽지 못한다 — cygpath 가 있으면 Windows 경로로 바꾼다(Rocky 에는 없다)
AUTH_FILE=$AUTH
if command -v cygpath >/dev/null 2>&1; then AUTH_FILE=$(cygpath -w "$AUTH"); fi

# api <메서드> <경로> — 본문은 표준 입력. 응답 본문은 표준 출력으로(단계마다 필요한 것만 골라 찍는다)
api() {
  curl -fsS -X "$1" "$BASE$2" -H @"$AUTH_FILE" -H 'Content-Type: application/json' -H 'X-Disable-Provenance: true' --data-binary @-
}

# 1. 알림 템플릿 — 연락처가 이름(rental.subject · rental.message)으로 부르므로 연락처보다 먼저 올린다.
#    작업 트리가 CRLF 로 체크아웃돼도 메일에 \r 가 섞이지 않게 줄 끝을 LF 로 맞춘다
"$PY" -c '
import json, sys
s = open("alerting/templates/rental.tmpl", encoding="utf-8").read().replace("\r\n", "\n")
sys.stdout.write(json.dumps({"template": s}))
' | api PUT /api/v1/provisioning/templates/rental > /dev/null
echo "1/4 템플릿 rental 반영"

# 2. 연락처 — 자리표시자를 실행 시 주소로 채운다. 주소는 출력하지 않는다
GRAFANA_ALERT_EMAIL="$GRAFANA_ALERT_EMAIL" "$PY" -c '
import json, os, sys
d = json.load(open("alerting/contact-point-email.json", encoding="utf-8"))
if d["settings"]["addresses"] != "${GRAFANA_ALERT_EMAIL}":
    sys.exit("contact-point-email.json 의 addresses 가 자리표시자가 아니다 — 저장소에 주소를 두지 않는다")
d["settings"]["addresses"] = os.environ["GRAFANA_ALERT_EMAIL"]
sys.stdout.write(json.dumps(d))
' | api PUT "/api/v1/provisioning/contact-points/$CONTACT_UID" > /dev/null
echo "2/4 연락처 $CONTACT_UID 반영"

# 3. 규칙 그룹 — alerting/rules-*.json 하나가 그룹 하나다(rental-backup — 백업 · 수집 · 인증서, rental-service — 자원 · 요청).
#    그룹 이름은 파일의 title 이다. 그룹 전체를 바꾼다(파일에 없는 규칙은 지워진다). 대기 · 평가 주기도 파일의 값이다.
#    파일을 지워도 그 그룹은 Grafana 에 남는다 — 그룹을 없앨 때는 화면에서 지운다
for f in alerting/rules-*.json; do
  GROUP=$("$PY" -c '
import json, sys
sys.stdout.write(json.load(open(sys.argv[1], encoding="utf-8"))["title"])
' "$f")
  # print 를 쓰지 않는다 — Windows 의 python 은 줄 끝을 \r\n 으로 써서 $( ) 뒤에 \r 이 남아 경로가 깨진다
  "$PY" -c '
import json, sys
sys.stdout.write(json.dumps(json.load(open(sys.argv[1], encoding="utf-8"))))
' "$f" | api PUT "/api/v1/provisioning/folder/$FOLDER_UID/rule-groups/$GROUP" > /dev/null
  echo "3/4 규칙 그룹 $GROUP 반영($f)"
done

# 4. 대시보드 — dashboards/ 의 JSON 을 모두 폴더 rental 에 각자의 UID 로 덮어쓴다. 파일을 더하면 이 단계가 함께 올린다
for f in dashboards/*.json; do
  RES=$("$PY" -c '
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
sys.stdout.write(json.dumps({"dashboard": d, "folderUid": "'"$FOLDER_UID"'", "overwrite": True, "message": "infra/grafana/apply.sh"}))
' "$f" | api POST /api/dashboards/db)
  OUT=$(printf '%s' "$RES" | "$PY" -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("url"), d.get("version"))
')
  read -r URL VER <<< "$OUT"
  echo "4/4 대시보드 $f 반영 — $BASE$URL (판 $VER)"
done
