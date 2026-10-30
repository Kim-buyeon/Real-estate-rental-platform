#!/usr/bin/env bash
# 커밋 차단 훅 시험 — 임시 저장소에 develop 을 만들고 규칙마다 막혀야 할 변경과 통과해야 할 변경을 스테이징해 훅을 돌린다.
#
#   bash .githooks/tests/pre-commit.test.sh
#
# 비밀처럼 보이는 표본은 실행 중에 조립한다. 이 파일 자체가 훅의 내용 검사에 걸리지 않게 하기 위해서다.
set -u

hook=$(cd "$(dirname "$0")/.." && pwd)/pre-commit
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work" || exit 2

git init -q -b develop
git config user.name test
git config user.email test@example.com
git config commit.gpgsign false
git config core.autocrlf false

migration=backend/src/main/resources/db/migration
mkdir -p "$migration"
printf 'create table a (id bigint);\n' > "$migration/V1__init.sql"
printf 'JWT_SECRET=\nPOSTGRES_PASSWORD=\nVITE_KAKAO_MAP_KEY=\n' > .env.example
printf "dependencies {\n\timplementation 'org.springframework.boot:spring-boot-starter-webmvc'\n}\n" > backend/build.gradle
git add -A
git commit -qm init
git switch -qc feature

passed=0
failed=0

# 결과를 pass · block · error 로 낸다. 차단 메시지 없이 실패하면 스크립트 오류다 — 막힌 것으로 세지 않는다
run_hook() {
  local out
  if out=$("$hook" "$@" 2>&1); then
    echo pass
  elif printf '%s' "$out" | grep -q '커밋 차단'; then
    echo block
  else
    echo error
  fi
}

# expect <block|pass> <이름> — 지금 스테이징된 변경으로 훅을 돌리고 결과를 비교한 뒤 작업 트리를 되돌린다
expect() {
  local want=$1 name=$2 got
  got=$(run_hook)
  if [ "$got" = "$want" ]; then
    passed=$((passed + 1))
    echo "ok    $name"
  else
    failed=$((failed + 1))
    echo "FAIL  $name — 기대 $want, 실제 $got"
  fi
  git reset -q --hard
  git clean -fdq
}

aws_key="AKIA$(printf 'Q%.0s' {1..16})"
private_key="-----BEGIN RSA PRIVATE ""KEY-----"

# 1. 자격 증명 — 파일
printf 'JWT_SECRET=x\n' > .env && git add -f .env
expect block ".env 추가"
mkdir -p infra && printf 'x\n' > infra/.env && git add -f infra/.env
expect block "하위 폴더 .env 추가"
printf 'x\n' > node.pem && git add node.pem
expect block "*.pem 추가"
printf 'x\n' > .env.local && git add -f .env.local
expect block ".env.local 추가"
mkdir -p chaos-harness/jmeter/accounts && printf 'id,pw\n' > chaos-harness/jmeter/accounts/users.csv
git add -f chaos-harness/jmeter/accounts/users.csv
expect block "JMeter 시험 계정 CSV 추가"
printf 'MAIL_PASSWORD=\n' >> .env.example && git add .env.example
expect pass ".env.example 에 빈 이름 추가"

# 1. 자격 증명 — 내용
printf 'key: %s\n' "$aws_key" > config.yml && git add config.yml
expect block "AWS 액세스 키"
printf '%s\n' "$private_key" > notes.txt && git add notes.txt
expect block "프라이빗 키 머리글"
printf 'JWT_SECRET=abc\n' >> .env.example && git add .env.example
expect block ".env.example 에 값 채움"
printf 'export POSTGRES_PASSWORD="hunter2"\n' > run.sh && git add run.sh
expect block "셸의 비밀 변수 대입"
printf 'POSTGRES_PASSWORD=${POSTGRES_PASSWORD}\nJWT_SECRET="$JWT_SECRET"\n' > run.sh && git add run.sh
expect pass "변수 참조 대입"
printf 'VITE_KAKAO_MAP_KEY=abc\n' > web.env.sample && git add web.env.sample
expect pass "화면 지도 키(비밀 아님)"

# 2. 적용된 마이그레이션
printf 'create table b (id bigint);\n' >> "$migration/V1__init.sql" && git add -A
expect block "적용된 V1 수정"
git rm -q "$migration/V1__init.sql"
expect block "적용된 V1 삭제"
git mv "$migration/V1__init.sql" "$migration/V1__renamed.sql"
expect block "적용된 V1 이름 변경"
printf 'create table c (id bigint);\n' > "$migration/V2__next.sql" && git add -A
expect pass "새 V2 추가"
printf 'create table c (id bigint);\n' > "$migration/V2__next.sql" && git add -A && git commit -qm v2
printf 'create table d (id bigint);\n' >> "$migration/V2__next.sql" && git add -A
expect pass "develop 에 없는 V2 수정"

# 3. 금지 의존성
sed -i "s/^}/\timplementation 'org.springframework.boot:spring-boot-starter-webflux'\n}/" backend/build.gradle && git add -A
expect block "webflux 추가"
sed -i "s/^}/\ttestImplementation 'com.h2database:h2'\n}/" backend/build.gradle && git add -A
expect block "h2 추가"
sed -i "s/^}/\tcompileOnly 'org.projectlombok:lombok'\n}/" backend/build.gradle && git add -A
expect pass "허용 의존성 추가"

# CI 범위 모드 — 커밋된 위반을 PR 범위로 잡는다
printf 'key: %s\n' "$aws_key" > leaked.yml && git add leaked.yml && git commit -qm leak --no-verify
if [ "$(run_hook --range develop...HEAD)" = block ]; then
  passed=$((passed + 1))
  echo "ok    범위 모드 — 커밋된 키"
else
  failed=$((failed + 1))
  echo "FAIL  범위 모드 — 기대 block"
fi
git reset -q --hard HEAD~1
if [ "$(run_hook --range develop...HEAD)" = pass ]; then
  passed=$((passed + 1))
  echo "ok    범위 모드 — 위반 없음"
else
  failed=$((failed + 1))
  echo "FAIL  범위 모드 — 기대 pass"
fi

echo
echo "통과 $passed · 실패 $failed"
[ "$failed" -eq 0 ]
