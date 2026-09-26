# 부하 시험 스크립트 (INF-06)

트래픽 정의서 8장의 프로파일 ↔ 스크립트 대응을 구현한다. 요청 조합 · 지도 단계 · 지역 분포 · 공통 파라미터는 `lib/mix.js` 한 곳에 있다. 판정 기준은 시험 계획서 2장이 정한다.

| 파일 | 프로파일 | executor |
|---|---|---|
| `baseline.js` | T1 기준선 10 RPS · 10분 | constant-arrival-rate |
| `steps.js` | T2 계단식 10→25→50→100→200 RPS, 5분 유지 · 2분 휴지 | ramping-arrival-rate |
| `steady.js` | T3 25 RPS · `DURATION` (T5 · T6 · T7 도 이것) | constant-arrival-rate |
| `sse.js` | SSE 동시 연결 (xk6-sse 필요) | constant-vus |
| `sse-hold.js` | SSE 연결 점유만 — 확장을 못 쓸 때의 대안 | constant-vus |
| `make-tokens.js` | 토큰 풀 만들기(준비 단계) | shared-iterations 1회 |
| `lib/mix.js` · `lib/tokens.js` · `lib/summary.js` | 공용 모듈 | — |
| `tools/stages.py` | `--out csv` 시계열의 단계별 집계 — 상태 코드 분포, 받아들인(2xx · 3xx) 처리량 · p95 · p99, 지도 2단계 p95. `python3 tools/stages.py results/t2.csv.gz` | — |

T4(`soak.js`)는 아직 없다.

## 필요한 것

- **k6 v2.x**(2.0 이상). 본 스크립트는 v1.x 에서도 돈다. `sse.js` 는 커뮤니티 확장 [xk6-sse](https://github.com/phymbert/xk6-sse) 가 필요하다.
  - **자동 확장 해석은 이 확장을 받지 못한다**(2026-09-26 LOAD-01 실측, k6 v2.3.0 — `unknown dependency : k6/x/sse` 로 곧바로 끝난다). 반드시 아래처럼 빌드한 바이너리로 돌린다. **SSE 실행 로그의 첫 줄을 확인한다** — SSE 가 곧바로 끝나도 본 프로파일은 그대로 돌아, 연결 점유 없이 측정된다(그날 T2 재측정 두 번이 그랬다).
  - 빌드: `xk6 build --with github.com/phymbert/xk6-sse@v0.2.0` 으로 빌드한 바이너리를 옮긴다(xk6-sse v0.2.0 은 k6 v2 대상, k6 v1.x 라면 v0.1.12).
- 대상 앞단 주소 `BASE_URL`(기본 `http://10.20.0.10` — APP-01 사설 IP의 앞단 Nginx).
- 매물 데이터가 적재돼 있어야 한다(트래픽 정의서 6장). setup 이 자치구별 마커 조회로 좌표 경계와 시드 매물을 구한다.

## 실행 순서

```bash
mkdir -p results
export LT_PASSWORD='...'            # 시험 계정 비밀번호. 8~64자

# 1. 토큰 풀 — 본 시험용과 SSE용은 계정 구간을 나눈다. **실행마다 직전에 다시 만든다**(아래 한계 1)
k6 run -e LT_PASSWORD=$LT_PASSWORD -e START=1    -e COUNT=100 -e OUT=tokens.json     make-tokens.js
k6 run -e LT_PASSWORD=$LT_PASSWORD -e START=1001 -e COUNT=250 -e OUT=tokens-sse.json make-tokens.js

# 2. SSE 를 먼저 띄워 연결을 확립한다(별도 터미널). 본 프로파일보다 길게
k6 run -e SSE_VUS=250 -e DURATION=45m -e TOKENS=tokens-sse.json -e SUMMARY_DIR=results sse.js

# 3. 연결이 다 열린 뒤(1~2분) 본 프로파일
k6 run -e SUMMARY_DIR=results baseline.js                       # T1
k6 run -e SUMMARY_DIR=results steps.js                          # T2
k6 run -e MAX_STEP=3 -e SUMMARY_DIR=results steps.js            # T2 50 RPS 까지(예비 측정, 5.1 1번)
k6 run -e DURATION=20m -e PROFILE=T3 -e SUMMARY_DIR=results steady.js
redis-cli FLUSHALL && k6 run -e DURATION=10m -e PROFILE=T5 -e SUMMARY_DIR=results steady.js
```

시계열이 필요하면(`dropped_iterations` 가 난 구간 찾기 등) `--out csv=results/t2.csv` 를 더한다.

## 환경 변수

| 변수 | 기본 | 쓰는 곳 | 뜻 |
|---|---|---|---|
| `BASE_URL` | `http://10.20.0.10` | 전부 | 앞단 주소 |
| `TOKENS` | `./tokens.json` · `./tokens-sse.json` | 본 · SSE | 토큰 파일. 상대 경로는 진입 스크립트 기준 |
| `SUMMARY_DIR` | 현재 디렉터리 | 전부 | 요약 JSON 을 쓸 곳(미리 만든다) |
| `DURATION` | (steady 필수) · `45m`(sse) | steady · sse | `600`, `20m`, `1h30m` |
| `PROFILE` | `T3` | steady | 요약 파일 이름에만 쓴다 |
| `MAX_STEP` | `5` | steps | 앞 N 단계만 |
| `THINK` / `THINK_MIN` / `THINK_MAX` | on / 3 / 10 | 본 | think time(초). `THINK=0` 이면 끈다 |
| `REQ_TIMEOUT_SEC` | `10` | 본 | 요청 하나의 상한 |
| `PRE_VUS` / `MAX_VUS` | 계산값 | 본 | 아래 「VU 수」 |
| `TOP_DISTRICTS` | 매물 수 상위 3개 | 본 | 지역 분포의 상위 자치구, 쉼표 구분 |
| `SSE_VUS` · `SSE_AUTH` · `HEARTBEAT_SEC` | 250 · `ticket` · 25 | sse | 동시 연결 · 인증 방식(`ticket`/`header`) · 하트비트 간격 |
| `LT_PASSWORD` · `START` · `COUNT` · `OUT` · `WISHLIST` · `CONCURRENCY` | — · 1 · 100 · `tokens.json` · 5 · 10 | make-tokens | 계정 비밀번호 · 번호 구간 · 출력 · 계정당 관심 매물 · 동시 요청 |

## 결과 읽기

- 요약 파일 `<스크립트>-<프로파일>-<시각>.json` 은 k6 요약 전체다. 화면에는 단계(phase) × 엔드포인트(ep) 표가 나온다 — 요청 수 · 실제 RPS · p95 · p99 · 검증 통과율 · 5xx 비율.
- 모든 요청에 태그가 붙는다: `ep`(엔드포인트, `lib/mix.js` 의 `EP_TAGS`), `name`(경로 틀), `phase`(`t1_10rps`, `s3_50rps` 등), `warmup`(`true`/`false`). 표와 임계는 `warmup:false` 만 집계한다 — 각 단계 첫 60초 제외(3.4).
- 임계(threshold)는 판정 기준을 그대로 걸어 둔 것이다: 단계별 · 엔드포인트별 p95 < 500ms, 5xx < 1%, `dropped_iterations` = 0. 실패하면 k6 가 종료 코드 99 로 끝나지만 시험은 멈추지 않는다.
- `dropped_iterations` > 0 이면 부하 생성기가 목표 RPS 를 못 낸 것이다. 그 구간은 결과에서 뺀다(8장).
- 지표: `lt_5xx`(5xx + 응답 없음), `lt_valid`(응답 검증 통과), `lt_empty`(배열이 빈 응답), `lt_expected_reject`(의도한 409 · 422 · 429), `lt_auth_expired_shared`(아래 한계 1). SSE 는 `lt_sse_*`.

## VU 수

arrival-rate 에서 반복 하나는 요청 하나 + think time 이다. think time 은 RPS 를 바꾸지 않고 동시 VU(= 동시 세션 · 열린 연결 수)를 실제 사용자 수에 가깝게 만든다.

- `preAllocatedVUs` = RPS × (평균 think 6.5초 + 1)
- `maxVUs` = RPS × (최대 think 10초 + 타임아웃 10초 + 1) — 포화 구간에서 응답이 타임아웃까지 늘어도 상한에 걸리지 않게(8장)

T2 의 200 RPS 단계는 maxVUs 4,200 이다. 생성기 사양이 부족하면 `THINK=0`(VU 가 수십~수백으로 준다)으로 돌리고 그 사실을 결과에 적는다.

## 한계

1. **토큰 회전.** 리프레시 토큰은 계정당 하나만 유효하다(재발급마다 회전). k6 는 VU 사이에 상태를 공유하지 못하므로 한 계정의 재발급은 그 계정의 첫 VU(소유자, VU 번호 ≤ 풀 크기)만 한다. 조합의 재발급 2% 는 소유자가 비중을 올려 맞춘다.
   - 풀(100)보다 VU 가 많고 시험이 액세스 토큰 수명(30분)보다 길면, 소유자가 아닌 VU 는 30분 뒤부터 인증 요청이 401 이다. `lt_auth_expired_shared` 로 따로 세며 서버 문제가 아니다. **T2(약 33분)를 think time 을 켠 채 돌리면 여기에 걸린다** — `COUNT` 를 `maxVUs` 이상으로 만들거나(가입은 한 번이면 되고 이후는 로그인만) 결과에서 그 건수를 뺀다.
   - 시험이 끝나면 파일의 리프레시 토큰은 이미 회전돼 쓸 수 없다. 실행마다 `make-tokens.js` 를 다시 돈다.
2. **알림 이벤트는 만들지 않는다.** 이벤트는 관심 매물의 등급 · 등기가 실제로 바뀌어야 생긴다. 조합의 재분석(1%)은 대부분 429(매물당 10분 간격)이고, 외부 연동이 Mock 이면 등급이 잘 안 바뀐다. `sse.js` 는 연결 · `:connected` · 하트비트 · (생기면) 이벤트와 지연을 잰다. 이벤트 도달을 확실히 보려면 SSE 계정이 관심 등록한 매물의 등급을 바꾸는 별도 수단이 필요하다.
3. **알림 목록은 비어도 실패로 세지 않는다.** 위 2 때문에 비어 있는 것이 정상일 수 있다. `lt_empty{ep:noti_list}` 로만 본다. 관심 매물 목록은 `make-tokens.js` 가 채워 두므로 비면 실패다.
4. **SSE 이벤트 지연**은 수신 시각 − `createdAt` 이라 생성기와 서버의 시계 차가 섞인다. 두 노드의 시각 동기 상태를 함께 적는다.
5. **SSE 분산**(두 인스턴스에 고르게, 3.5)은 스크립트가 정할 수 없다 — 앞단 upstream 이 나눈다. 앱 쪽 연결 수 지표로 확인한다.
6. **`GET .../risk` 는 조회 시점에 분석을 수행한다**(결과가 바뀌면 이력을 쓴다). 조회 부하라기보다 계산 · 쓰기가 섞인 부하다.
7. `steps.js` 총 길이는 약 33분이다(5 × 5분 + 4 × 2분). 문서의 「약 40분」과 다르다.
8. setup 은 자치구마다 서울 전체 범위의 마커 조회를 한 번씩(25회) 보낸다. 측정에는 들지 않지만(phase 태그 없음) 시험 직전 캐시 · 버퍼 상태를 바꾼다.
