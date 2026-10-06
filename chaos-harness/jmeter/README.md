# 엔드포인트 부하 시험 — JMeter(INF-06 #390)

엔드포인트 부하 · 스트레스 회차를 JMeter 로 돌린다. 매물 100만 시험(#390 「최종 부하 · 스트레스 시험」)은 `endpoints.jmx` 하나로 로그인을 포함한 22개 엔드포인트와 혼합을 요청하고(엔드포인트 · 섞는 비율 · 토큰 풀은 [ENDPOINTS.md](ENDPOINTS.md)), `rounds-1m.sh` 가 회차를 이어 돌린다. `login.jmx` 는 지난 회차(10/4)의 로그인 전용 플랜이다. 회차를 재고 남기는 순서(기록 · 수집 · 집계)는 [측정 README](../measure/README.md)가 갖는다 — 여기 `run.sh` 는 그 「회차마다」의 3번이고, `rounds-1m.sh` 는 1 ~ 5 를 회차마다 부른다. 양식 칸마다 어느 요소 · 회차가 채우는지는 [coverage.md](coverage.md).

```
jmeter/
├── login.jmx · endpoints.jmx     플랜(속성은 각 파일 머리 주석)
├── run.sh · user.properties      회차 실행 — jtl · 대시보드 · steps.json · round.json, jtl 칸 고정
├── rounds-1m.sh                  회차 목록을 이어 돌린다 — 자동 중단 · 합격 CSV · 예상 시간
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

매물 100만 시험(#390 「최종 부하 · 스트레스 시험」, 10/5 마감 조정 — 회차 사이 30초). 회차 목록 · 길이 · 선행 조건의 정본은 `rounds-1m.sh` 의 회차 표이고, `--list` 가 표와 예상 시간을 보인다(오늘 밤 전체 약 4시간 6분 — 측정 스크립트 시간 · 스트레스 조기 중단은 빼고 셈).

| 묶음 | 회차 | 내용 | 도는 방법 |
| --- | --- | --- | --- |
| 부하 — 단독 | L-U01 ~ L-U22 | 엔드포인트마다 max(혼합 비율 × 60, 5) RPS — 30초 증가 + 2분 고정 | 자동 |
| 부하 — 혼합 | L-R01 ~ L-R03 · L-R04 | 60 RPS × 5분 × 3 · 60 RPS × 15분 + SSE 242 | 자동 |
| 부하 — 조건 | L-R10 · L-R11 · L-R12 | 추적 끔 · 읽기 분산 끔 · 배포 직후 — 60 RPS × 5분 + SSE 242 | 운영자가 조건을 바꾸고 `--round` |
| 스트레스 — 단독 | S-U01 ~ S-U22 | 그 부하 회차가 합격한 것만. 부하 RPS → 10배를 3분 동안 연속 증가, 기준을 넘으면 중단 → 한계 기록 | 자동 |
| 스트레스 — 혼합 | S8 · S2 · S3 · S4 · S7 · S-SPIKE | L-R04 합격 뒤. 계단 60 → 240(+15 / 3분, 상한 4P — 중단 조건이 먼저 끊는다) · 120 RPS × 5분 · 쏠림 60 % × 5분 · 로그인 몰림 242 / 10초 + 60 RPS × 3분(워밍업 0, L-U21 합격도) · keep-alive 없음 + SSE 1000 × 5분 · 급증 0 → 180 RPS / 10초 × 3분 | 자동 |
| 스트레스 — 조건 | S5 · S6 | 캐시 비움 직후 60 RPS × 5분 · 배치 동시 × 10분 | 운영자가 조건을 바꾸고 `--round` |
| 상한 확인 | S-429 | 예외가 아닌 주소에서 30 RPS × 2분 — 429 가 나오면 합격. **부하 생성기가 아닌 곳(운영자 PC)에서, `HOST=<입구 공인 IP>`** | `--round S-429` |
| 지속 | SOAK | 30 RPS × 30분(무인) — 오늘 밤 목록에 없다 | `--soak` |
| 혼합 한계 | S8L · S8M | 혼합 60 RPS 불합격 뒤(10/5 사용자 결정) — 20 부터 +5 · 10 부터 +2 RPS / 3분 | `--round` |
| 안정 부하 | B-R04 · B-R12 · B-R10 · B-R11 · B-S5 · B-S6 · B-SOAK | 혼합 한계 아래 `STABLE` RPS(10/5 는 12)에서 기준(측정 모드 켬) · 배포 직후 · 추적 끔 · 읽기 분산 끔 · 캐시 비움 직후 · 배치 동시 · 지속 2시간 | 운영자가 조건을 바꾸고 `--round` |

10/5 ~ 6 에 실제로 돈 것: 부하 단독 22 · 혼합 60 RPS 4 → 혼합 한계 둘 → 안정 부하 12 RPS(B-S6 배치 동시는 돌지 않았다) · 단독 스트레스(부하 합격분) · 지속 2시간. S2 · S3 · S4 · S7 · S-SPIKE · S-429 · L-R10 ~ L-R12 는 혼합 60 RPS 불합격으로 돌지 않았다. 결과와 바뀐 조건은 결과서(`부하시험-결과보고서-20261006`) [1.3] · [11.1].

```bash
J=chaos-harness/jmeter
bash $J/rounds-1m.sh --list                         # 회차 표 · 예상 시간(돌지 않는다)
HOST=<APP-01 사설 IP> bash $J/rounds-1m.sh --phase load      # 부하 자동 회차
HOST=<APP-01 사설 IP> bash $J/rounds-1m.sh --round L-R10     # 운영자가 추적을 끈 뒤(L-R11 · L-R12 · S5 · S6 도 같은 꼴)
HOST=<APP-01 사설 IP> bash $J/rounds-1m.sh --phase stress    # 합격한 것만 스트레스
HOST=<입구 공인 IP> bash $J/rounds-1m.sh --round S-429       # 운영자 PC 에서
```

- **자동 중단** — 10초 창(setup 레이블 제외) 두 개 연속 p95 > 500 ms 또는 5xx > 1 %, 또는 앱 노드 MemAvailable < 250 MiB(메모리를 세 번 연속 못 읽어도). 그 회차만 멈추고(StopTestNow) 다음으로 간다. 회차 **시작 전** MemAvailable 이 250 MiB 미만이면 목록 전체를 멈춘다 — 슬롯 재시작은 운영 작업이다.
- **판정 · 기록** — `results/rounds-1m.csv` 에 회차마다 한 줄(pass · fail · limit · skip, p95 · 오류 · 5xx · 처리량 · 한계 RPS). 스트레스는 선행 회차가 pass 인 것만 돈다. 재시작 0 은 여기서 보지 않는다(pre-round 기록 · 집계).
- **이어 돌기** — 같은 명령을 다시 부르면 CSV 에 있는 회차(skip 제외)는 건너뛴다. 끝나지 않은 회차 폴더는 `<회차>.cut-<시각>` 으로 옮기고 다시 돈다. `--redo` 면 다시 돈다.
- 회차마다 측정 README 「회차마다」의 1 ~ 5 를 부른다(`MEASURE=none` 이면 JMeter 만 — 로컬 스모크). 회차 사이 30초 안에 record stop · collect · 다음 pre-round 가 들어간다 — 그보다 길면 사이가 늘어난다.
- **준비 구간** — `ABORT_GRACE_S`(기본 0) 초 동안의 창은 자동 중단 판정에서 뺀다. 시작 직후 계정 로그인 몰림 · 캐시 데우기가 판정을 멈추게 한 일이 있어 10/5 ~ 6 은 안정 부하 · 지속 60, 단독 스트레스 30, 캐시 비운 직후 0(첫머리 꼬리를 보려고)으로 돌렸다.
- 길이 · 기준 · SSE 수는 환경 변수로 바꾼다(파일 머리 주석). 지난 회차(10/4, 계획 변경 3)의 명령 꼴은 git 이력에 있다.

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
