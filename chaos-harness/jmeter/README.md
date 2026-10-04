# 엔드포인트 부하 시험 — JMeter(INF-06 #390)

실제로 구현된 엔드포인트 중 20개(계획 변경 3)의 부하 회차를 JMeter 로 돌린다. 로그인은 `login.jmx`, 나머지 19개는 `endpoints.jmx`(엔드포인트 · 섞는 비율 · 토큰 풀은 [ENDPOINTS.md](ENDPOINTS.md)). 회차를 재고 남기는 순서(기록 · 수집 · 집계)는 [측정 README](../measure/README.md)가 갖는다 — 여기 `run.sh` 는 그 「회차마다」의 3번이다. 양식 칸마다 어느 요소 · 회차가 채우는지는 [coverage.md](coverage.md).

```
jmeter/
├── login.jmx · endpoints.jmx     플랜(속성은 각 파일 머리 주석)
├── run.sh · user.properties      회차 실행 — jtl · 대시보드 · steps.json · round.json, jtl 칸 고정
├── make-accounts.sh              시험 계정 · 모드별 CSV(저장소 밖 secret/)
├── accounts-status.sql           운영 계정 상태(읽기 전용)
├── queries/                      실제 값 EXPLAIN 의 질의 정의(login.yaml · endpoints.yaml)
├── bench/BcryptBench.java        BCrypt 대조 단위 측정
└── local/compose.trace.yml       로컬 측정 모드 추적(질의 정의 대조용)
```

## 설치 — JMeter 5.6.3 · 플러그인 둘

이 PC 에는 설치하지 않았다. 부하 생성기(LOAD-01, Linux)와 로컬 스모크를 할 PC 에 둔다. Java 17 이상(로컬은 21).

```bash
# 1. JMeter — 아파치 보관소의 5.6.3 과 SHA-512 대조
curl -fLO https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz
curl -fLO https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz.sha512
sha512sum -c apache-jmeter-5.6.3.tgz.sha512 && tar xzf apache-jmeter-5.6.3.tgz
J=$PWD/apache-jmeter-5.6.3

# 2. 플러그인 관리자(명령줄) — 관리자 jar 는 lib/ext, cmdrunner 는 lib
curl -fL -o $J/lib/ext/jmeter-plugins-manager-2.0.jar https://repo1.maven.org/maven2/kg/apc/jmeter-plugins-manager/2.0/jmeter-plugins-manager-2.0.jar
curl -fL -o $J/lib/cmdrunner-2.3.jar https://repo1.maven.org/maven2/kg/apc/cmdrunner/2.3/cmdrunner-2.3.jar
java -cp $J/lib/ext/jmeter-plugins-manager-2.0.jar org.jmeterplugins.repository.PluginManagerCMDInstaller

# 3. 플러그인 — Custom Thread Groups(Concurrency Thread Group) 3.1.1 · Throughput Shaping Timer 2.6
$J/bin/PluginsManagerCMD.sh install jpgc-casutg=3.1.1,jpgc-tst=2.6
$J/bin/PluginsManagerCMD.sh status            # 두 줄이 보이면 된다
export JMETER=$J/bin/jmeter                   # run.sh 가 읽는다(Windows 는 …/bin/jmeter.bat)
```

판은 2026-10-04 jmeter-plugins.org 저장소 목록의 최신이다. 다른 판을 쓰면 회차 기록(`NOTES`)에 남긴다. 플러그인 판에 맞는 설치는 `PluginsManagerCMD.sh install-for-jmx login.jmx` 로도 된다.

HTTPS — JMeter 의 HTTP 요청은 인증서를 검증하지 않는다(사용자 설명서 「Getting Started」). 그래서 LOAD-01 → APP-01 **사설 IP** 443(인증서 SAN 은 공인 IP)이 그대로 된다. 사설 IP 로 보내는 이유는 nginx 의 IP 상한 예외가 사설 주소(`10.20.10.50`)에만 걸리기 때문이다(`infra/nginx/conf.d/default.conf`).

## 실행 — run.sh

```bash
bash chaos-harness/jmeter/run.sh <회차> <mode> <load_profile> [bg_rps] [-- <JMeter 인자> …]
```

| 자리 | 값 |
| --- | --- |
| mode | `valid` · `wrong` · `enum`(login.jmx) · `-`(넘기지 않음 — endpoints.jmx) |
| load_profile | `const(N,T)` `line(N,K,T)` `step(N,K,S,T)` 를 이어 쓴다 — Throughput Shaping Timer 문법(jmeter-plugins.org 위키). N · K · S 는 **정수**(플러그인이 `Integer.parseInt`), T 는 초 또는 `2m` · `1m30s`. `step` 은 N 부터 K 까지(K 포함). 0 RPS 는 쓰지 않는다(위키). `burst:<계정 수>,<초>` 면 몰림 |
| bg_rps | 지도 2단계 고정 부하(login.jmx 의 일반 요청 그룹). 0 이면 없다 |
| `--` 뒤 | JMeter 에 그대로 붙인다 — 플랜마다 다른 속성(`-- -Jtarget=map-clusters`). 같은 `-J` 를 다시 주면 뒤가 이긴다 |

플랜은 `PLAN`(기본 `login.jmx`), 대상은 `HOST` · `PORT` · `PROTOCOL`(기본 `localhost` · `443` · `https`), 계정은 `ACCOUNTS_DIR`. 나머지 환경 변수(`HEAP` · `SIGNUP` · `KIND` · `WARMUP_SEC` …)와 결과 파일의 모양은 `run.sh` 머리 주석이 정본이다. 결과는 `chaos-harness/measure/results/<회차>/` 의 `jmeter/result.jtl` · `jmeter/html/` · `steps.json` · `round.json`. 결과가 이미 있으면 덮지 않고 멈춘다.

## 계정

**운영 계정 상태부터 본다**(읽기 전용, standby). 표가 크면 먼저 `EXPLAIN` 만 붙여 돌린다(파일 머리 주석).

```bash
bash -c '. chaos-harness/measure/node/lib.sh; psql_node db02 < chaos-harness/jmeter/accounts-status.sql'
```

`email_jmeter_with_hash` 가 회차에 쓸 수(100 — #390 승인 계획 R05 규모)보다 적으면 만들고 가입한다.

```bash
bash chaos-harness/jmeter/make-accounts.sh jmeter 100          # 원본 secret/jmeter_accounts.csv(있으면 그대로) → secret/jmeter/*.csv
HOST=<APP-01 사설 IP> SIGNUP=true bash chaos-harness/jmeter/run.sh A00 valid 'const(1,30s)'   # 가입 setUp(201 · 409) + 짧은 로그인
```

가입은 운영 DB 에 쓰는 일이다 — **운영 작업**, 확인을 받고 한다. 원본은 지우지 않는다(비밀번호를 다시 알 수 없다). LOAD-01 에서 돌리면 `secret/jmeter/` 를 올려 `ACCOUNTS_DIR` 로 주고, 시험이 끝나면 지운다.

## 로컬 추적 스모크

운영 회차 전에 질의 정의(`queries/*.yaml`)와 BCrypt 구간을 실제 추적과 맞춘다. 로컬 디스크 여유를 먼저 본다 — 이미지 빌드와 추적 파일이 쌓인다.

```bash
bash chaos-harness/measure/node/trace-receiver.sh start                                # 127.0.0.1:4318 → measure/trace-data/spans.jsonl
docker compose -f docker-compose.yml -f chaos-harness/jmeter/local/compose.trace.yml up -d --build
bash chaos-harness/jmeter/make-accounts.sh jmeter 5
HOST=localhost PORT=5173 PROTOCOL=http SIGNUP=true MAX_THREADS=5 \
  bash chaos-harness/jmeter/run.sh L01 valid 'step(1,5,2,30s)'                        # wrong · enum · endpoints.jmx 도 같은 꼴로
mkdir -p chaos-harness/measure/results/L01/traces && cp chaos-harness/measure/trace-data/spans.jsonl chaos-harness/measure/results/L01/traces/
bash chaos-harness/measure/node/trace-receiver.sh stop
docker compose up -d app                                                                # 덧씌움 없이 다시 — 평시
```

대조할 것 — 로그인 추적 하나의 `db.statement` 넷(인증 수단 조회 · 회원 지연 로딩 · findById · UPDATE)이 `queries/login.yaml` 의 열 순서 · 별칭과 같은지, 메서드 구간에 `BCryptPasswordEncoder.matchesNonNull` 이 있는지. 맞으면 `login.yaml` 머리의 「대조 전」 줄을 지운다. 로컬은 내보내기 둘이 같은 수신기로 가서 **구간이 두 번씩** 들어온다(`compose.trace.yml` 머리 주석).

## 회차

계획 변경 2 · 3(#390 코멘트, 2026-10-04 승인). 비율 · 계단 숫자(`<…>`)는 확인 ②(단위 측정 · 예비 회차)와 ③(흔들림)에서 정한다 — 정하기 전에는 비워 둔다. 회차 길이는 계획 변경 3 으로 줄였다(흔들림 3분 · 단독 계단 4단계 × 90초 · 혼합 계단 단계당 2분 · 워밍업 30초 · 간격 2분) — 시간 제약, 결과서 [1.3] 에 적는다. 엔드포인트 이름(`-Jtarget`)과 U02 ~ U20 의 대응은 [ENDPOINTS.md](ENDPOINTS.md).

| 회차 | 내용 | 명령 꼴 |
| --- | --- | --- |
| R01 ~ R03 | 흔들림 — 트래픽 정의서 3장 비율로 섞은 낮은 고정 부하 × 3 | `KIND=jitter WARMUP_SEC=30 PLAN=chaos-harness/jmeter/endpoints.jmx bash chaos-harness/jmeter/run.sh R01 - 'const(<N>,3m)' -- -Jtarget=mix` |
| U01 | 단독 — 로그인 계단(4단계 × 90초) + 지도 2단계 고정 부하(R01 수준) — [6.2] 「로그인 몰림 중 조회 p95」 | `bash chaos-harness/jmeter/run.sh U01 valid 'step(<a>,<b>,<s>,90s)' <R01 의 지도 RPS>` |
| U02 ~ U09 | 단독 — 엔드포인트마다 계단(4단계 × 90초) | `PLAN=chaos-harness/jmeter/endpoints.jmx bash chaos-harness/jmeter/run.sh U02 - 'step(<a>,<b>,<s>,90s)' -- -Jtarget=<엔드포인트>` |
| U10 ~ U20 | 단독 — 추가 11개(조회 4 · 재발급 · 쓰기 6) 고정 부하 2분. 한계는 찾지 않는다(p95 · 시간 예산 · 단가 · SQL) | `KIND=solo PLAN=chaos-harness/jmeter/endpoints.jmx bash chaos-harness/jmeter/run.sh U10 - 'const(<N>,2m)' -- -Jtarget=<엔드포인트>` |
| R04 | S8 — 혼합 계단(단계당 2분) | `KIND=mix PLAN=chaos-harness/jmeter/endpoints.jmx bash chaos-harness/jmeter/run.sh R04 - 'step(<a>,<b>,<s>,<T>)' -- -Jtarget=mix` |
| R05 | S4 — 로그인 몰림 100개 · 10초 | `bash chaos-harness/jmeter/run.sh R05 valid 'burst:100,10'` |
| P01 | 프로파일 — R04 의 한계 직전 혼합 부하. 회차 도중 `node/profiler.sh`(운영 작업) | `PROFILER=<노드 슬롯 모드> KIND=mix PLAN=chaos-harness/jmeter/endpoints.jmx bash chaos-harness/jmeter/run.sh P01 - 'const(<R04 한계 직전>,6m)' -- -Jtarget=mix` |
| E01 | 20개 엔드포인트의 SQL 실제 값 EXPLAIN — 쓰기 포함, 맨 마지막, 끝나면 VACUUM. JMeter 를 돌리지 않는다 | `bash chaos-harness/measure/node/explain.sh E01 chaos-harness/jmeter/queries/login.yaml chaos-harness/jmeter/queries/endpoints.yaml --allow-write` — **운영 작업** |

`wrong` · `enum` 모드는 계획 변경 1 로 이번 회차에서 뺐다(플랜에는 남아 있다).

## 단위 측정 — BCrypt 대조 한 번

요청 밖, 한 스레드에서 BCrypt 강도 10(앱과 같은 인코더) `matches` 의 시간을 잰다. 로그인 시간 중 BCrypt 몫의 기준선이다. 클래스패스는 앱의 판(Spring Boot 4.1.1) — spring-security-crypto 7.1.1 · spring-core 7.0.9 · commons-logging 1.3.6.

```bash
# 로컬 컴파일 — Gradle 캐시의 jar(경로는 PC 마다 다르다)
B=chaos-harness/jmeter/bench G=$(cygpath -m ~/.gradle/caches/modules-2/files-2.1)    # Linux 는 cygpath 없이 경로 그대로
mkdir -p $B/out/lib
cp $G/org.springframework.security/spring-security-crypto/7.1.1/*/spring-security-crypto-7.1.1.jar \
   $G/org.springframework/spring-core/7.0.9/*/spring-core-7.0.9.jar \
   $G/commons-logging/commons-logging/1.3.6/*/commons-logging-1.3.6.jar $B/out/lib/
javac --release 21 -cp "$B/out/lib/*" -d $B/out $B/BcryptBench.java
java -Dstdout.encoding=UTF-8 -cp "$B/out/lib/*;$B/out" BcryptBench 50 200        # Linux 는 ; 대신 :

# 노드 실행 — out/(클래스 + lib/ jar 셋)을 노드 /tmp/rental-bench 로 올리고 eclipse-temurin:21-jre(앱 실행 이미지의 바탕)에서
docker run --rm --memory 256m -v /tmp/rental-bench:/b:ro eclipse-temurin:21-jre java -cp '/b/lib/*:/b' BcryptBench 50 200
```

출력은 `matches_mean_ms` · `p50` · `p95` · `min` · `max`(ms). 노드에서는 슬롯과 같은 CPU 를 나눠 쓰므로 부하가 없을 때 돌린다. 이미지가 노드에 없으면 받는다(약 450 MB) — 디스크를 먼저 본다.
