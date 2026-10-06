# 엔드포인트 플랜(endpoints.jmx) 사용법

INF-06(#390) 매물 100만 시험의 엔드포인트 22개(로그인 포함)와 혼합(mix)을 요청한다. 회차를 이어 돌리는 것은 `rounds-1m.sh`(README 「회차」). `login.jmx` 는 지난 회차(10/4)의 로그인 전용 플랜이고, 이번 회차는 로그인도 이 플랜으로 돈다.

| 파일 | 하는 일 |
| --- | --- |
| `endpoints.jmx` | 플랜. 속성은 파일 머리 주석 |
| `rounds-1m.sh` | 회차 목록을 이어 돌린다 — 자동 중단 · 합격 기록 · 예상 시간 |
| `sql/property-ids.sql` | 매물 ID 표본 CSV 를 만든다(읽기 전용) |
| `queries/endpoints.yaml` | 대상마다 요청 하나가 내는 SQL — E01 · 질의 속도가 읽는다 |

## 1. 준비

1. **계정** — `make-accounts.sh` 가 만든 `valid.csv`(열 email,password). run.sh 가 `-Jaccounts` 로 넘긴다.
   - 계정 하나는 한 번에 한 스레드만 쓴다. 반복마다 빌렸다가 돌려놓는다. 리프레시 토큰이 사용자당 하나라서(RefreshTokenStore), 두 스레드가 같은 계정을 쓰면 재발급 · 로그인이 서로의 토큰을 밀어낸다. SSE 묶음 · 로그인 몰림 묶음도 같은 줄에서 빌린다.
   - **계정 수가 동시 스레드 수 이상이어야 한다.** 모자라면 스레드가 빌릴 계정을 기다린다. 그 대기는 부하 생성기 쪽 지연이고, jmeter.log 에 `빌릴 계정이 5초째 없다` 가 남는다. S4(몰림 242)는 계정이 242 + 혼합 스레드 수 이상이어야 몰림이 10초 안에 다 나간다.
   - 시험 계정에 관심 매물 · 알림이 있다고 본다(적재 — `chaos-harness/load/data/`). 알림 읽음은 그 계정의 **읽지 않은 알림**부터 읽는다(2장).
   - `login.jmx` 와 **동시에** 돌리면 다른 계정 묶음을 쓴다. 같은 계정을 쓰면 그 플랜의 로그인이 이 플랜의 리프레시 토큰을 바꿔 `auth/reissue` 가 401 이 된다.
2. **매물 표본** — `sql/property-ids.sql`. 파일 머리의 「EXPLAIN 먼저」를 따른다. 출력은 계정 CSV 와 같은 폴더의 `property-ids.csv` 다(그 자리가 기본값이다). 다른 자리에 두면 `-- -Jproperty_ids=<경로>` 로 준다.
   - 고르는 조건: 최신 판정 · 등기 · 대장이 모두 있는 매물. 위험도 · 대출 · 대장 · 등기 조회가 요청 안에서 외부 API 를 부르지 않게 하기 위해서다.
   - 플랜은 표본을 고르게 뽑는다 — 자치구마다 같은 수라 자치구도 고르게 나온다. `tier` 열은 쏠림(S3)의 기본 자치구를 정할 때만 쓴다.
3. **지도 표시 영역** — 준비하지 않는다. 플랜이 요청마다 화면과 같은 식으로 만든다(2장 `map-clusters`).
4. **계정 프로필** — 준비하지 않는다. `loans/limit` 이 막히는 경우는 「주택 보유인데 연소득 0」 하나뿐이다(422 `PROFILE_INCOMPLETE` — API 명세서(대출) 1.1, `LoanCommandService`). 가입 API 는 `has_house = false` 로 만든다. 대출 한도가 막히는 다른 경우는 매물 쪽이다(가입 불가 → 422). 그래서 `loans/limit` 은 표본 중 `insurance_eligible = t` 인 매물만 쓴다.

## 2. 대상(`-Jtarget`)

레이블은 analyze 의 엔드포인트 키다(경로에서 `/api/` 를 떼고 숫자를 `{id}` 로 — analyze README). 같은 키에 요청이 둘이면 뒤에 종류를 붙인다. 집계는 URL 로 키를 만들어 엔드포인트별로 묶고, 레이블로 종류를 가른다.

모든 요청에 `Accept-Encoding: gzip` 을 붙인다 — 화면(브라우저)이 늘 보내는 값이고, 응답 크기 · nginx 압축 CPU 가 실제와 같아진다. JMeter(HttpClient4)가 받은 gzip 을 풀어 검증한다.

문장 수와 노드는 `queries/endpoints.yaml` 머리 표를 본다. 「DB 에 남기는 것」은 운영 DB 에 남는 행 증가 · 갱신이다.

| target | 요청 | 레이블 | DB 에 남기는 것 |
| --- | --- | --- | --- |
| `district-counts` | GET `/api/properties/district-counts`(필터 없음 — 서울 단계 첫 화면) | `properties/district-counts` | 없음(Redis 캐시 키 하나, TTL 10분) |
| `map-clusters` | GET `/api/properties/map-clusters?district&minLat&maxLat&minLng&maxLng&rows&cols` — 아래 「지도 묶음의 모양」 | `properties/map-clusters` | 없음(슬롯 로컬 캐시) |
| `properties-radius` | GET `/api/properties?district&lat&lng&radiusKm=1`(중심 = 표본 매물 좌표). 화면은 부르지 않는다 — 혼합 0 % | `properties radius` | 없음 |
| `properties-list` | GET `/api/properties?…` — 아래 「목록의 모양」 | `properties list` | 없음 |
| `property-detail` | GET `/api/properties/{id}` | `properties/{id}` | 없음 |
| `property-risk` | GET `/api/properties/{id}/risk` | `properties/{id}/risk` | 결론이 바뀐 매물만 `risk_analysis` 갱신 1 + 삽입 1. 표본은 판정이 있어 보통 없다 |
| `wishlist-read` | GET `/api/me/wishlist`(첫 쪽, size 없음). 받은 매물 ID 를 계정에 둔다 | `me/wishlist` | 없음 |
| `wishlist-add` | POST `/api/me/wishlist` — 계정에 없는 표본 매물. 201 · 409(이미 등록) 모두 성공 | `me/wishlist add` | `wishlist` 삽입 1 — **회차 뒤에도 남는다**(아래) |
| `wishlist-remove` | DELETE `/api/me/wishlist/{id}` — 이 시험이 등록한 것 → 관심 목록 첫 쪽에서 본 것 → 표본 순 | `me/wishlist/{id} remove` | `wishlist` 삭제 1(멱등 — 없으면 0) |
| `wishlist-write` | 등록 → 해제 짝(단독 회차 L-U16 · S-U16) | `me/wishlist add` · `me/wishlist/{id} remove` | 짝마다 삽입 1 · 삭제 1 — 행은 남지 않는다 |
| `loans-limit` | GET `/api/loans/limit?propertyId`(가입 가능 매물) | `loans/limit` | `property-risk` 와 같다 |
| `notifications` | GET `/api/notifications`(첫 쪽) | `notifications` | 없음 |
| `ledger` | GET `/api/properties/{id}/ledger` | `properties/{id}/ledger` | 없음 |
| `registry` | GET `/api/properties/{id}/registry` | `properties/{id}/registry` | 없음 |
| `profile-read` | GET `/api/me/profile` | `me/profile` | 없음 |
| `profile-write` | PUT `/api/me/profile` | `me/profile update` | 시험 계정의 `users` 갱신(연소득 4,200만/4,500만 번갈아 …). **회차 뒤에도 남는다** |
| `subscriptions-read` | GET `/api/me/notification-subscriptions` | `me/notification-subscriptions` | 없음 |
| `subscriptions-write` | PUT `/api/me/notification-subscriptions` | `me/notification-subscriptions update` | 계정의 `notification_subscription` 을 지우고 5행 삽입, `wishlist.monitoring_yn` 갱신 |
| `reissue` | POST `/api/auth/reissue`(그 계정의 refreshToken, 새 토큰으로 갈아 끼움) | `auth/reissue` | 없음(Redis 회전) |
| `notification-read` | PATCH `/api/notifications/{id}/read` — 아래 「알림 읽음」 | `notifications/{id}/read` · `… again` · `… none` | 읽지 않은 알림의 `is_read` 를 참으로 |
| `notification-read-all` | PATCH `/api/notifications/read-all` | `notifications/read-all` | 그 계정의 읽지 않은 알림 전부 `is_read` 참 |
| `login` | POST `/api/auth/login` — 빌린 계정의 토큰을 새 것으로 바꾼다 | `auth/login` | `user_auth.last_login_at` 갱신 |
| `logout` | POST `/api/auth/logout`. 단독이면 다음 반복이 준비 로그인, 혼합이면 다음에 그 계정을 빌린 반복이 `login`(3장) | `auth/logout` | 없음(Redis 키 삭제) |
| `stream-ticket` | POST `/api/notifications/stream-ticket` | `notifications/stream-ticket` | 없음(Redis 티켓, 30초) |
| `mix` | 3장 | 위 레이블들 | 위 행들의 합 |

**지도 묶음의 모양** — 화면(`features/property/map/map.ts` 의 `readMapArea` · `roundOutward` · `gridSize`, 상수는 `constants.ts`)과 같은 식이다.

1. 중심 = 그 요청의 표본 매물 좌표(그 자치구 안 여러 자리). 레벨 = 7 · 8 · 9 중 하나(고르게). 7 은 자치구 진입 레벨(`DISTRICT_LEVEL`), 8 · 9 는 축소다. `-Jmap_levels=1,2,…,9` 면 확대해 보는 레벨(1 ~ 6)까지 고르게 — 타일이 작아 서로 다른 영역이 많아지고 지도 캐시 적중이 낮다
2. 화면 = 중심 ± 실측 화면 폭 / 2(레벨 7 에서 위도 0.09765 × 경도 0.26692도, 레벨마다 두 배 — constants.ts 「타일 크기」 표).
3. 그 레벨의 타일(위도 512 · 경도 1,024 단위 × 0.0001도 — 레벨마다 두 배) 배수로 밖으로 맞춘다 — min 내림 · max 올림. 결과는 위도 2 ~ 3 타일 × 경도 3 ~ 4 타일이다.
4. rows = 위도 타일 수 × 6, cols = 경도 타일 수 × 5(상한 24 — 넘으면 타일당 칸을 줄인다). 그래서 rows 12 · 18, cols 15 · 20.
5. 질의 순서는 화면과 같다 — `district`(자치구 단계의 필터) · `minLat` · `maxLat` · `minLng` · `maxLng` · `rows` · `cols`. 좌표는 소수 4자리 이하(JS 숫자 문자열과 같게 끝 0 을 뺀다).

같은 자리 · 같은 레벨이면 사용자와 무관하게 같은 키라 서버 캐시(`MapClusterCache`)가 적중한다. 자치구 안 자리를 흩뜨리므로 적중률은 「구마다 한 영역」보다 낮고 실제에 가깝다. 화면의 필터 바 값은 지도 묶음에 붙이지 않았다(아래 5장).

**목록의 모양** — 화면(`fetchPropertyList(filter, sort, cursor)`)처럼 `{...필터, sort, cursor}` 이고 `size` 는 보내지 않는다(공통 규약 1.4 기본 20). 필터는 필터 바가 고를 수 있는 값만이다 — `contractType`(셋 중 하나) · `depositMax`(1억 · 2억 · 3억 · 5억) · `riskGrade`(`SAFE` 또는 `SAFE` + `CAUTION` — 같은 키 반복) · `district`(자치구 단계). 정렬은 없음(서버 기본 = 등록일 내림차순) 또는 다섯 중 하나. 직전 목록 응답에 다음 쪽이 있으면 그 계정의 다음 목록 요청 일부가 같은 조건 + `cursor` 로 다음 쪽을 부른다(무한 스크롤). 확률은 setUp 스크립트의 `ep_list` — 근거가 없는 시험 가정이다(5장).

**알림 읽음** — 계정마다 처음 한 번 목록(size 100, 레이블 `notifications setup`)에서 읽지 않은 알림 · 읽은 알림의 ID 를 받는다. 그 뒤 읽지 않은 것을 하나씩 읽는다(`notifications/{id}/read` — 실제 UPDATE). 다 읽은 계정은 읽은 것을 다시 읽음 처리한다(`… again`, 200 · UPDATE 없음). 알림이 하나도 없는 계정은 없는 알림 `0` 을 보낸다(`… none`, 404 `NOTIFICATION_NOT_FOUND` 를 성공으로 센다). 몇 계정이 `again` · `none` 으로 갔는지는 jmeter.log 에 남는다. 전체 읽음 뒤에는 그 계정의 읽지 않은 목록을 읽은 쪽으로 옮긴다.

**모든 대상의 공통 준비 요청** — `auth/login setup`(계정마다 처음, 만료 100초 전, 401 뒤, 단독 로그아웃 뒤)과 `notifications setup`(알림 읽음 대상에서 계정마다 한 번)은 초당 요청 수에 들지 않는다. 레이블이 ` setup` 으로 끝나 analyze 집계 · run.sh 의 계단 시작 · rounds-1m.sh 의 중단 판정에서 빠진다.

**관심 매물 등록이 남기는 행** — 혼합의 등록(1.9 %)이 해제(0.6 %)보다 많아 회차마다 행이 남는다(예: 60 RPS × 15분이면 등록 약 1,000 · 해제 약 300). 해제는 이 시험이 넣은 것부터 지운다. 시험이 끝나면 시험 계정의 그 시각 이후 `wishlist` 행을 지운다 — **운영 작업**이다.

**SSE 묶음(`-Jsse=N`)** — 스레드 N 개가 각자 계정을 잠깐 빌려 `notifications/stream-ticket sse setup` 으로 티켓을 받고, 돌려놓은 뒤 `GET /api/notifications/stream?ticket=` 을 회차 끝까지 붙잡는다(JSR223 — 연결 시간이 표본 하나, 레이블 `notifications/stream sse setup`). 연결 수명은 토큰 남은 시간(앱 `notification.sse.timeout`)이라 서버가 닫으면 새 티켓으로 다시 연다 — 화면 `NotificationStream` 과 같다. 하트비트(25초)를 90초 못 받으면 끊긴 것으로 센다. 연결 수 · 메모리는 앱 지표(`notification.sse.connections`) · 노드 지표로 본다. 열리는 속도는 `-Jsse_ramp_s`(기본 30초).

**로그인 몰림(`-Jlogin_burst=N`)** — `burst_delay_s`(기본 30초) 뒤 `login_burst_s`(기본 10초) 동안 계정마다 한 번 로그인한다(`auth/login burst`). 타이머 밖이라 혼합의 초당 요청 수와 따로 나간다.

**쏠림(`-Jskew_pct=P`)** — 요청의 P % 를 한 자치구(`skew_district`, 기본 표본의 첫 top 자치구)로 보낸다. 그 몫의 지도 묶음은 그 구 표본 좌표의 가운데 · 레벨 7 의 같은 화면, 목록은 그 구 · 필터 없음, 매물 ID 요청은 그 구의 표본이다.

## 3. mix 비율

**근거 — develop 화면의 호출 흐름.** 화면 코드(`api/*.ts` · MapExplorer · PropertyDetailPanel · NotificationStream · queryClient)를 따라 DAU 1명이 하루에 내는 요청을 셌다(#390 계획 「정한 것」). 트래픽 정의서는 쓰지 않는다 — 최적화 전 문서다(#390 사용자 지시).

| 요청 | 1인 하루 | 비율(÷ 53.65) | P = 60 RPS 일 때 |
| --- | --- | --- | --- |
| `map-clusters` | 17 | 31.7 % | 19.0 |
| `property-detail` | 7.3 | 13.6 % | 8.2 |
| `properties-list` | 7 | 13.0 % | 7.8 |
| `property-risk` | 6 | 11.2 % | 6.7 |
| `loans-limit` | 6 | 11.2 % | 6.7 |
| `notifications` | 2.6 | 4.8 % | 2.9 |
| `reissue` · `stream-ticket` · `district-counts` · `ledger` · `registry` · `wishlist-add` | 각 1 | 각 1.9 % | 각 1.1 |
| `wishlist-read` | 0.5 | 0.9 % | 0.56 |
| `wishlist-remove` · `notification-read` | 각 0.3 | 각 0.6 % | 각 0.34 |
| `notification-read-all` | 0.1 | 0.2 % | 0.11 |
| `profile-read` · `subscriptions-read` | 각 0.15 | 각 0.3 % | 각 0.17 |
| `profile-write` · `subscriptions-write` | 각 0.05 | 각 0.1 % | 각 0.06 |
| `login` | 0.1 | 0.2 % | 0.11 |
| `logout` | 0.05 | 0.1 % | 0.06 |
| `properties-radius` | 0 | 0 % | — |
| 합 | 53.65(≈ 54) | 100 % | 60 |

**목표 부하 P = 60 RPS** — 다방 MAU 80만(와이즈앱 2026-05) × 서울 비중 36.4 %(2025-09 서울 84,193 ÷ 전국 231,000) × DAU/MAU 13.7 % × 1인 하루 54요청 × 피크 시간 10 % ÷ 3,600초 ≈ 60. 직방 기준(약 100 RPS)은 쓰지 않는다(사용자 결정).

플랜에 옮기는 규칙(setUp 스크립트의 `MIX`):

- 가중치 = 1인 하루 요청 수 × 100(합 5,365). 반복 하나가 요청 하나다(관심 매물 등록 · 해제가 따로라 짝이 없다).
- **로그인은 10 이 아니라 5 로 둔다.** 혼합에서 로그아웃(5)한 계정은 다음에 빌려질 때 로그인부터 한다(화면: 로그아웃 → 다시 로그인). 그 몫 5 와 무작위 5 를 합쳐 10 이 된다. 로그인은 빌린 계정에서만 일어나 그 계정의 토큰을 새 것으로 바꾼다 — 그 토큰을 쓰는 다른 스레드가 없으니 재발급이 401 로 이어지지 않는다.
- 반경(`properties-radius`)은 화면이 부르지 않아 0 이다. 단독 회차(L-U08 · S-U08)에서만 잰다.
- 지역 — 표본을 고르게 뽑는다(자치구마다 같은 수). 자치구 편중은 쏠림 회차(S3)에서만 준다.
- 응답 검증은 상태 코드 + `$.success` 다(JMeter Best Practices — 최소). think time 은 없다 — 열린 모델이라 타이머가 시작 간격을 정한다.
- SSE 연결 · 로그인 몰림은 타이머 밖 묶음이다(2장). 혼합의 `stream-ticket` 은 티켓만 받고 연결은 열지 않는다 — 연결은 SSE 묶음이 연다.

## 4. 회차 명령

회차는 `rounds-1m.sh` 가 이어 돌린다(README 「회차」). 단독 회차의 초당 요청 수는 max(위 표의 P = 60 값, 5) 의 반올림이다 — 5 RPS 미만이면 2분에 600건이 안 돼 p95 를 쓸 수 없다. 회차 하나만 손으로 돌리려면 run.sh 를 직접 부른다(mode 자리 `-`, 대상은 `--` 뒤).

```bash
P=chaos-harness/jmeter/endpoints.jmx
PLAN=$P bash chaos-harness/jmeter/run.sh L-U01 - 'line(1,19,30s) const(19,2m)' -- -Jtarget=map-clusters
KIND=mix PLAN=$P bash chaos-harness/jmeter/run.sh L-R04 - 'line(1,60,30s) const(60,870s)' -- -Jtarget=mix -Jsse=242
```

다른 -J 를 더 줄 때도 `--` 뒤에 둔다(예: `-- -Jtarget=mix -Jaccounts=C:/…/endpoints.csv -Jproperty_ids=…`).

## 5. 확인하지 못한 것 · 알려 둘 것

- **운영에서 돌린 것과 아직 안 돈 것.** 10/5 ~ 6 매물 100만 시험에서 이 플랜으로 54회차를 돌렸다(LOAD-01, 결과서 「부하시험-결과보고서-20261006」) — 단독 22개 · 혼합(SSE 242 포함) · 혼합 한계 · 안정 부하 · 지속. 그때 드러난 도구 문제(계정 회전으로 요청마다 로그인 · 타이머가 초당 약 1건 덜 보냄 · 시작 직후 로그인 몰림 · SSE 가 종료를 붙잡음)는 고쳤다(README 「회차」). **아직 돌지 않은 요소** — 로그인 몰림 묶음의 지연 시작(S4) · `Connection: close`(S7) · 급증(S-SPIKE) · 쏠림(S3): 혼합 60 RPS 불합격으로 뒤 회차를 돌지 않았다. 아래는 운영 전 로컬에서 확인한 것이다(로컬에 JMeter 는 없다 — 설치 금지) — ① XML 파싱 · 요소 짝(hashTree) ② Groovy 스크립트 전부의 문법(Groovy 4.0 파서 — JMeter 클래스는 클래스패스에 없어 import 한 줄을 빼고) ③ setUp · 고르기 스크립트를 가짜 vars · props 로 10만 번 돌린 분포(mix 비율 · 로그아웃 뒤 로그인 · 격자 rows/cols · 쏠림 · 목록 질의 문자열) ④ rounds-1m.sh 를 가짜 jmeter 로 돌린 중단 · 판정 · 건너뜀. JMeter 요소의 실제 동작은 로컬 스모크에서 본다 — SSE JSR223 연결 · 끝 시각 끊기, JSON 추출기의 필터 식(`[?(@.isRead == false)]`), 응답 검증 OR(201 · 409), `Connection: close`, 로그인 몰림 묶음의 지연 시작.
- **목록 · 지도 레벨의 확률은 근거가 없다(미확정).** 필터 바 사용률 · 정렬 사용률 · 다음 쪽 비율 · 레벨 7 ~ 9 의 고른 분포는 화면 계측이 없어 정한 시험 가정이다. 화면 계측(관측 작업)이 생기면 그것으로 바꾼다.
- **지도 묶음 · 구별 집계에는 필터를 붙이지 않았다.** 화면은 필터 바 값을 둘에도 보낸다. 필터를 쓰는 사용자가 많으면 실제 캐시 적중률은 이 시험보다 낮다.
- **화면 크기는 데스크톱 실측 하나(1474 × 677)다.** 모바일 폭은 미실측이라(constants.ts) 덮는 타일 수가 더 적은 요청은 없다.
- **`district-counts` 는 필터 없이 부른다.** 캐시 키가 하나라 회차 대부분이 Redis 적중이다.
- **`queries/endpoints.yaml` 의 SQL 은 develop 매퍼와 다를 수 있다.** 이번에는 지도 묶음의 칸 수(rows · cols)만 바꿨다. V22 이후 매물의 등급 열 비정규화 등 매퍼 변경은 로컬 추적 대조 때 맞춘다.
- `sql/property-ids.sql` 머리 주석의 근거(트래픽 정의서 3.3 · 7장)는 그대로다 — 표본을 만드는 규칙은 바꾸지 않았고, 플랜이 `tier` 를 편중에 쓰지 않을 뿐이다.

## 6. E01 값 채우기

`queries/endpoints.yaml` 의 `${…}` 를 표본 첫 줄(top 묶음 · 가입 가능)과 그 좌표 중심의 지도 화면(레벨 7)으로 바꾼 사본을 만들어 explain.sh 에 준다. 영역 · 행 · 열 · 칸은 플랜 · 화면 · 앱과 같은 식이다 — 영역은 2장 「지도 묶음의 모양」, 칸 = 영역 ÷ rows · cols, 끝 칸 번호 = rows − 1 · cols − 1(`PropertyQueryService.loadMapClusters`), 반경 박스는 `GeoDistanceCalculator.boundingBox`(1 km)다.

```bash
python - <ACCOUNTS_DIR>/property-ids.csv chaos-harness/jmeter/queries/endpoints.yaml jmeter+001@rental.test > /tmp/endpoints.e01.yaml <<'EOF'
import csv, math, re, sys
from decimal import Decimal, ROUND_HALF_UP
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
r = next(x for x in rows if x["tier"] == "top" and x["insurance_eligible"] in ("t", "true"))
lat, lng, R = float(r["latitude"]), float(r["longitude"]), 6371.0088
# 지도 화면 — 레벨 7: 실측 화면 0.09765 × 0.26692, 타일 512 · 1,024 단위(0.0001도), 타일당 칸 6 · 5, 상한 24
sc = lambda v: float(Decimal(repr(v * 10000)).quantize(Decimal("0.000001"), ROUND_HALF_UP))
ul, ug = 512, 1024
mnla, mxla = math.floor(sc(lat - 0.09765 / 2) / ul) * ul, math.ceil(sc(lat + 0.09765 / 2) / ul) * ul
mnlo, mxlo = math.floor(sc(lng - 0.26692 / 2) / ug) * ug, math.ceil(sc(lng + 0.26692 / 2) / ug) * ug
div = lambda span, u, per: min(24, max(1, round(span / u)) * min(per, max(1, 24 // max(1, round(span / u)))))
nr, nc = div(mxla - mnla, ul, 6), div(mxlo - mnlo, ug, 5)
d = lambda u: format(Decimal(u).scaleb(-4).normalize(), "f")
dla, dlo = math.degrees(1 / R), math.degrees(1 / (R * math.cos(math.radians(lat))))
v = {"PROPERTY_ID": r["property_id"], "DISTRICT": r["district"], "PROPERTY_TYPE": r["property_type"], "EMAIL": sys.argv[3],
     "MIN_LAT": d(mnla), "MAX_LAT": d(mxla), "MIN_LNG": d(mnlo), "MAX_LNG": d(mxlo), "ROWS": str(nr), "COLS": str(nc),
     "CELL_LAT": repr((float(d(mxla)) - float(d(mnla))) / nr), "CELL_LNG": repr((float(d(mxlo)) - float(d(mnlo))) / nc),
     "MAX_ROW_INDEX": str(nr - 1), "MAX_COL_INDEX": str(nc - 1),
     "R_MIN_LAT": repr(lat - dla), "R_MAX_LAT": repr(lat + dla), "R_MIN_LNG": repr(lng - dlo), "R_MAX_LNG": repr(lng + dlo)}
t = open(sys.argv[2], encoding="utf-8").read()
for k, x in v.items():
    t = t.replace("${" + k + "}", x)
assert not re.search(r"\$\{[A-Z_]+\}", t), "채우지 못한 자리표시가 있다"
sys.stdout.buffer.write(t.encode("utf-8"))
EOF
bash chaos-harness/measure/node/explain.sh E01 /tmp/endpoints.e01.yaml [--allow-write]
```

쓰기 질의(kind: write)는 `--allow-write`(BEGIN … ROLLBACK)에서만 돈다. 끝나면 VACUUM 한다 — 승인 계획 E01 · 「ROLLBACK 해도 흔적이 남는다」.
