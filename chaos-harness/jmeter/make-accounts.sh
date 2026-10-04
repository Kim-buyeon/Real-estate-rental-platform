#!/usr/bin/env bash
# 시험 계정과 모드별 CSV 를 만든다(INF-06 #390). 운영에 아무것도 쓰지 않는다 — 가입은 run.sh 의 SIGNUP=true(가입 API)가 한다.
#
#   bash make-accounts.sh <접두어> <개수> [출력 폴더]
#     예) bash make-accounts.sh jmeter 100       →  jmeter+001@rental.test … jmeter+100@rental.test
#
# 원본 — ACCOUNTS_SRC(기본 C:/Users/bu200/loadtest-data/secret/jmeter_accounts.csv), 머리 줄 email,password,name.
#   계정마다 무작위 비밀번호(영문 대소문자 · 숫자 20자 — 가입 정책 8 ~ 64자 · UTF-8 72바이트 안, PasswordPolicy).
#   **원본이 있으면 그 계정을 그대로 쓴다(덮지 않는다).** 그 접두어의 계정이 모자라면 모자란 번호만 끝에 더한다.
#   비밀번호를 바꾸면 이미 가입한 계정으로 로그인할 수 없다 — 원본은 저장소 밖(secret/)에만 두고 지우지 않는다.
# 출력 폴더(기본 C:/Users/bu200/loadtest-data/secret/jmeter) — 원본의 앞 <개수> 계정으로 매번 다시 쓴다(login.jmx 머리 주석의 열)
#   valid.csv    email,password                 맞는 비밀번호
#   wrong.csv    email,password                 있는 계정 · 틀린 비밀번호(실행마다 새로 뽑는다)
#   enum.csv     email,password,exists          있는 계정 절반(exists) · missing-NNN@loadtest.invalid 절반(missing)을 번갈아,
#                                               비밀번호는 둘 다 틀린 값 — 계정 열거(있음 · 없음의 응답 시간 차이)
#   signup.csv   email,password,name            가입 setUp 용
# 파일은 소유자만 읽게(600) 둔다. 비밀번호는 화면에 내지 않는다 — 개수 · 경로만 출력한다.
set -euo pipefail

usage() { echo "사용법: bash make-accounts.sh <접두어> <개수> [출력 폴더]" >&2; exit 2; }
[ $# -ge 2 ] && [ $# -le 3 ] || usage
PREFIX=$1 COUNT=$2
OUT=${3:-C:/Users/bu200/loadtest-data/secret/jmeter}
SRC=${ACCOUNTS_SRC:-C:/Users/bu200/loadtest-data/secret/jmeter_accounts.csv}
# 접두어는 이메일 로컬 부분 앞머리다 — 정규식 · CSV 에 그대로 들어가므로 글자를 좁힌다
[[ $PREFIX =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { echo "접두어는 영문 소문자 · 숫자 · _ · - 만: $PREFIX" >&2; exit 2; }
[[ $COUNT =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 2 ] || { echo "개수는 2 이상의 정수(enum 이 절반씩 나눈다): $COUNT" >&2; exit 2; }

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }
umask 077

# 번호 자리수 — 최소 3(001), 개수가 더 크면 그 자리수. 999 를 넘겨 다시 만들면 자리수가 바뀌어 다른 이메일이 된다
WIDTH=${#COUNT}; [ "$WIDTH" -ge 3 ] || WIDTH=3
email_of() { printf '%s+%0*d@rental.test' "$PREFIX" "$WIDTH" "$1"; }

# 무작위 20자. /dev/urandom 300바이트에서 영숫자만 남긴다(약 72자) — 파이프 앞을 정해진 길이로 끊어 SIGPIPE 를 피한다
rand_pw() {
  local s=""
  while [ ${#s} -lt 20 ]; do s+=$(head -c 300 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9'); done
  printf '%s' "${s:0:20}"
}

mkdir -p "$(dirname "$SRC")" "$OUT"
if [ ! -f "$SRC" ]; then
  printf 'email,password,name\n' > "$SRC"
  log "원본 새로 만듦 — $SRC"
fi
head -1 "$SRC" | tr -d '\r' | grep -qx 'email,password,name' || { echo "원본 머리 줄이 email,password,name 이 아니다: $SRC" >&2; exit 1; }

# 원본에서 이 접두어의 계정 — 번호 순
RE="^${PREFIX}\\+[0-9]+@rental\\.test,"
have=$(grep -cE "$RE" "$SRC" || true)
added=0
if [ "$have" -lt "$COUNT" ]; then
  # 있는 번호는 건너뛰고 빈 번호만 더한다 — 원본의 줄은 바꾸지 않는다
  for i in $(seq 1 "$COUNT"); do
    e=$(email_of "$i")
    awk -F, -v e="$e" '$1 == e { f = 1 } END { exit !f }' "$SRC" && continue
    printf '%s,%s,%s %0*d\n' "$e" "$(rand_pw)" "$PREFIX" "$WIDTH" "$i" >> "$SRC"
    added=$((added + 1))
  done
fi
log "원본 — 이 접두어 계정 ${have}개 있었고 ${added}개 더함"

# 앞 COUNT 계정(번호 순). 원본 줄 = email,password,name
# head 대신 awk — 앞에서 끊으면 sort 가 SIGPIPE 로 죽고 pipefail 이 그것을 실패로 본다
rows=$(grep -E "$RE" "$SRC" | tr -d '\r' | sort -t, -k1,1 | awk -v n="$COUNT" 'NR <= n')
[ "$(printf '%s\n' "$rows" | wc -l | tr -d ' ')" -eq "$COUNT" ] || { echo "원본에서 계정 $COUNT 개를 고르지 못했다" >&2; exit 1; }

write() {  # <파일 이름> — 표준 입력을 임시 파일에 받아 옮긴다(반쯤 쓴 파일이 남지 않게)
  local tmp="$OUT/.$1.tmp"
  cat > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$OUT/$1"
}

{ echo 'email,password'; printf '%s\n' "$rows" | cut -d, -f1,2; } | write valid.csv
{ echo 'email,password,name'; printf '%s\n' "$rows"; } | write signup.csv
{
  echo 'email,password'
  printf '%s\n' "$rows" | cut -d, -f1 | while read -r e; do printf '%s,wrong-%s\n' "$e" "$(rand_pw)"; done
} | write wrong.csv
# enum — 있는 쪽 앞 절반 · 없는 쪽 절반을 번갈아. 없는 이메일은 예약 최상위 도메인 .invalid(RFC 2606)라 실제 주소가 아니다
HALF=$((COUNT / 2))
{
  echo 'email,password,exists'
  i=0
  printf '%s\n' "$rows" | cut -d, -f1 | awk -v n="$HALF" 'NR <= n' | while read -r e; do
    i=$((i + 1))
    printf '%s,wrong-%s,exists\n' "$e" "$(rand_pw)"
    printf 'missing-%0*d@loadtest.invalid,wrong-%s,missing\n' "$WIDTH" "$i" "$(rand_pw)"
  done
} | write enum.csv

for f in valid wrong enum signup; do
  log "$OUT/$f.csv — $(( $(wc -l < "$OUT/$f.csv") - 1 ))줄"
done
