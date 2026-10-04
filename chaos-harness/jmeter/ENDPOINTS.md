# 엔드포인트 플랜(endpoints.jmx) 사용법

INF-06(#390) 계획 변경 2 · 3 의 범위 20개 중 로그인을 뺀 19개 엔드포인트를 요청한다. 로그인은 `login.jmx` 다. 나중에 같은 폴더 README 에 합친다.

| 파일 | 하는 일 |
| --- | --- |
| `endpoints.jmx` | 플랜. 속성은 파일 머리 주석 |
| `sql/property-ids.sql` | 매물 ID 표본 CSV 를 만든다(읽기 전용) |
| `queries/endpoints.yaml` | 대상마다 요청 하나가 내는 SQL — E01 · 질의 속도가 읽는다 |

## 1. 준비

1. **계정** — `make-accounts.sh` 가 만든 `valid.csv`(열 email,password). run.sh 가 `-Jaccounts` 로 넘긴다.
   - 계정 하나는 한 번에 한 스레드만 쓴다. 반복마다 빌렸다가 돌려놓는다. 리프레시 토큰이 사용자당 하나라서(RefreshTokenStore), 두 스레드가 같은 계정을 쓰면 재발급 · 로그인이 서로의 토큰을 밀어낸다.
   - **계정 수가 동시 스레드 수 이상이어야 한다.** 모자라면 스레드가 빌릴 계정을 기다린다. 그 대기는 부하 생성기 쪽 지연이고, jmeter.log 에 `빌릴 계정이 5초째 없다` 가 남는다.
   - 로그인 플랜(`login.jmx`)과 **동시에** 돌리면 다른 계정 묶음을 쓴다. 예: `make-accounts.sh` 를 다른 접두어로 돌려 `-- -Jaccounts=…` 로 준다. 같은 계정을 쓰면 로그인 플랜이 이 플랜의 리프레시 토큰을 바꿔 `auth/reissue` 가 401 이 된다.
2. **매물 표본** — `sql/property-ids.sql`. 파일 머리의 「EXPLAIN 먼저」를 따른다. 출력은 계정 CSV 와 같은 폴더의 `property-ids.csv` 다(그 자리가 기본값이다). 다른 자리에 두면 `-- -Jproperty_ids=<경로>` 로 준다.
   - 고르는 조건: 최신 판정 · 등기 · 대장이 모두 있는 매물. 위험도 · 대출 · 대장 · 등기 조회가 요청 안에서 외부 API 를 부르지 않게 하기 위해서다.
   - 개수: 자치구마다 200건(최대 5,000건). 근거는 SQL 머리 주석 — 트래픽 정의서 7장 「데이터 편중」.
3. **지도 표시 영역** — 따로 준비하지 않는다. 플랜이 표본 좌표에서 자치구마다 계산한다(2 · 98 백분위). 다른 영역을 쓰려면 `-- -Jbbox=<CSV>` 를 준다(열 `district,tier,min_lat,max_lat,min_lng,max_lng`).
4. **계정 프로필** — 준비하지 않는다. `loans/limit` 이 막히는 경우는 「주택 보유인데 연소득 0」 하나뿐이다(422 `PROFILE_INCOMPLETE` — API 명세서(대출) 1.1, `LoanCommandService`). 가입 API 는 `has_house = false` 로 만든다(`User` 생성 · V1 기본값). 그래서 그대로 계산된다. 대출 한도가 막히는 다른 경우는 매물 쪽이다(가입 불가 → 422). 그래서 `loans/limit` 은 표본 중 `insurance_eligible = t` 인 매물만 쓴다.

## 2. 대상(`-Jtarget`)

레이블은 analyze 의 엔드포인트 키다(경로에서 `/api/` 를 떼고 숫자를 `{id}` 로 — analyze README). 같은 키에 요청이 둘이면 뒤에 종류를 붙인다. 집계는 URL 로 키를 만들어 엔드포인트별로 묶고, 레이블로 종류를 가른다. 반경과 목록은 URL 경로가 같아(`properties`) 엔드포인트 통계에서는 하나로 합쳐지고, 레이블 표(`properties radius` · `properties list`)에서만 갈린다.

문장 수와 노드는 `queries/endpoints.yaml` 머리 표를 본다. 「DB 에 남기는 것」은 운영 DB 에 남는 행 증가 · 갱신이다. 판정이 그대로인 정상 경로를 기준으로 적었다.

| target | 요청 | 레이블 | DB 에 남기는 것 |
| --- | --- | --- | --- |
| `district-counts` | GET `/api/properties/district-counts`(필터 없음) | `properties/district-counts` | 없음(Redis 캐시 키 하나, TTL 10분) |
| `map-clusters` | GET `/api/properties/map-clusters?district&minLat&maxLat&minLng&maxLng`(그 구의 표시 영역) | `properties/map-clusters` | 없음 |
| `properties-radius` | GET `/api/properties?district&lat&lng&radiusKm=1`(중심 = 표본 매물 좌표) | `properties radius` | 없음 |
| `properties-list` | GET `/api/properties?district&size=20`(기본 정렬, 첫 페이지) | `properties list` | 없음 |
| `property-detail` | GET `/api/properties/{id}` | `properties/{id}` | 없음 |
| `property-risk` | GET `/api/properties/{id}/risk` | `properties/{id}/risk` | 결론이 바뀐 매물만 `risk_analysis` 갱신 1 + 삽입 1(등급이 바뀌면 알림 이벤트까지). 표본은 판정이 있어 보통 없다 |
| `wishlist-read` | GET `/api/me/wishlist`(첫 페이지) | `me/wishlist` | 없음 |
| `wishlist-write` | POST `/api/me/wishlist` → DELETE `/api/me/wishlist/{id}`(짝) | `me/wishlist add` · `me/wishlist/{id} remove` | 짝마다 `wishlist` 삽입 1 · 삭제 1 — 행은 남지 않고 죽은 튜플 · 식별자 증가만 남는다. 회차가 짝 사이에서 끊기면 한 행이 남는다 |
| `loans-limit` | GET `/api/loans/limit?propertyId`(가입 가능 매물) | `loans/limit` | `property-risk` 와 같다 |
| `notifications` | GET `/api/notifications`(첫 페이지) | `notifications` | 없음 |
| `ledger` | GET `/api/properties/{id}/ledger` | `properties/{id}/ledger` | 없음(대장이 있는 매물이라 수집하지 않는다) |
| `registry` | GET `/api/properties/{id}/registry` | `properties/{id}/registry` | 없음(위와 같은 이유) |
| `profile-read` | GET `/api/me/profile` | `me/profile` | 없음 |
| `profile-write` | PUT `/api/me/profile` | `me/profile update` | 시험 계정의 `users` 갱신 — 이름 `jmeter` · 전화 null · 연소득 4,200만/4,500만 번갈아 · 신용점수 820 · 기존 대출 0 · 무주택 · 자기자금 5,000만. **회차 뒤에도 남는다** |
| `subscriptions-read` | GET `/api/me/notification-subscriptions` | `me/notification-subscriptions` | 없음 |
| `subscriptions-write` | PUT `/api/me/notification-subscriptions` | `me/notification-subscriptions update` | 시험 계정의 `notification_subscription` 을 지우고 5행 삽입(요청마다 — 계정당 5행으로 바뀌고 죽은 튜플 · 식별자 증가), 그 계정 `wishlist.monitoring_yn` 갱신 |
| `reissue` | POST `/api/auth/reissue`(그 계정의 refreshToken, 응답의 새 토큰으로 갈아 끼움) | `auth/reissue` | 없음(Redis 리프레시 토큰 회전) |
| `notification-read` | PATCH `/api/notifications/{id}/read` — 계정의 알림 ID 를 처음 한 번 목록(size 100)에서 받는다 | `notifications/{id}/read` · 알림이 없는 계정은 `notifications/{id}/read none`(아래) | 읽지 않은 알림이면 `notification.is_read` 를 참으로. 한 번 읽은 알림은 다시 바뀌지 않는다(뒤로는 조회만) |
| `notification-read-all` | PATCH `/api/notifications/read-all` | `notifications/read-all` | 그 계정의 읽지 않은 알림 전부 `is_read` 참(첫 요청 뒤로는 0행 갱신) |
| `logout` | POST `/api/auth/logout` → 다음 반복이 같은 계정으로 다시 로그인(짝) | `auth/logout` | 없음(Redis 키 삭제). 짝 로그인이 `user_auth.last_login_at` 을 갱신한다 |
| `stream-ticket` | POST `/api/notifications/stream-ticket` | `notifications/stream-ticket` | 없음(Redis 티켓, 30초) |
| `properties` | `properties-radius` · `properties-list` 를 18 : 14 로 | 위 두 레이블 | 없음 |
| `wishlist` | `wishlist-read` · `wishlist-write` 를 반복 12 : 3 으로(요청 6% : 3%) | 위 세 레이블 | `wishlist-write` 와 같다 |
| `mix` | 아래 3장 | 위 레이블들 | 위 행들의 합 |

**모든 대상의 공통 준비 요청** — `auth/login setup`(계정마다 처음, 만료 100초 전, 401 뒤, 로그아웃 뒤)이 `user_auth.last_login_at` 을 갱신한다(login.yaml 의 `login_update_last_login`). `notifications setup` 은 `notification-read` 대상에서만, 계정마다 한 번 나간다. 둘 다 초당 요청 수에 들지 않는다. 다만 레이블이 아니라 URL 로 묶는 엔드포인트 통계에는 `auth/login` · `notifications` 로 섞여 들어간다(아래 5장).

**알림이 없는 계정** — 시험 계정에 알림이 없으면 `notification-read` 는 없는 알림 `0` 을 읽음 처리한다. 레이블은 `notifications/{id}/read none` 이고, 404 `NOTIFICATION_NOT_FOUND` 를 기대값(성공)으로 센다. 이때 재는 것은 「조회 한 번 → 없음」 경로이고 UPDATE 는 없다. 몇 계정이 이 경로를 탔는지는 jmeter.log 의 `알림 없음` 줄로 센다. 결과서에는 그 회차가 쓰기를 재지 못했다고 적는다. `notification-read-all` 도 읽지 않은 알림이 없으면 0행 UPDATE 다.

## 3. mix 비율

비율은 트래픽 정의서 3.1(요청 조합) · 3.2(지도 탐색 단계) · 3.3(지역 분포)을 그대로 따른다. 숫자를 여기 옮겨 적지 않는다 — 표는 그 문서에 있다. 여기에는 표를 반복 가중치로 바꾼 규칙만 적는다(`endpoints.jmx` setUp 스크립트의 `MIX`).

- 3.1 의 행 중 범위 밖인 재분석(RISK-08)은 뺀다. 나머지는 3.1 의 비중 그대로다. 남은 합이 99라서 각 엔드포인트의 실제 비중은 표의 값 ÷ 0.99 다.
- 3.1 의 PROP-02 행은 3.2 의 세 단계로 나눈다. 1단계(시 전체 · 자치구 단위 집계)는 `district-counts` 다. 서버에 따로 조회가 없고, 명세(매물 1.2)가 서울 전체 단계의 호출을 그것으로 정했다. 2단계(자치구)는 `map-clusters`, 3단계(동 · 거리 계산)는 `properties-radius` 다. **해석이다** — 3.1 이 `district-counts` 를 따로 22% 로 두었으므로, 1단계 몫이 그 위에 더해진다.
- `wishlist-write` 는 반복 하나가 요청 둘이다. 그래서 반복 가중치를 요청 비중의 절반으로 둔다. 타이머는 요청(샘플러)마다 세므로 요청 비중이 3.1 과 같아진다.
- 3.3 은 대상과 무관하게 요청마다 건다. 상위 3개 자치구 묶음 60 %, 나머지 40 %. 상위 3개는 문서에 없어서 표본의 매물 수 순위로 정한다(`property-ids.sql`).
- 로그인은 이 플랜에 없다. 계획 변경 3 의 혼합 회차(R01 ~ R04)에 로그인을 섞으려면 `login.jmx` 를 같은 시각에 따로 돌려야 한다. 그때 두 플랜의 초당 요청 수를 나누는 비율은 3.1 에 없다 — 로그인은 3.4 「사전 발급 토큰」으로 빠져 있다. 그래서 정해야 할 값이다(아래 5장).
- 응답 검증은 3.4 「배열 길이 확인」 대신 상태 코드 + `$.success` 다. 승인 계획 「JMeter Best Practices — 최소」를 따른다.
- think time 은 없다. 열린 모델이라 타이머가 시작 간격을 정한다.

## 4. 회차 명령

run.sh 는 플랜을 `PLAN` 으로 받는다. mode 자리에는 `-` 를 쓴다 — `-Jmode` 를 넘기지 않고 계정은 `valid.csv` 다. 대상은 `--` 뒤 `-Jtarget` 으로 준다. bg_rps 는 쓰지 않는다(이 플랜에는 일반 요청 그룹이 없다). 그래서 `--` 를 바로 붙인다. 계단의 초당 요청 수는 정수만 된다. 계단 숫자(N · K · S)는 계획 변경 3 의 확인 지점에서 정한다. 아래 `<…>` 는 그때 채운다.

```bash
# 단독 계단 4단계 × 90초 (계획 변경 3 — U02 ~ U09). step(N,K,S,90s) 는 N 부터 K 까지 S 씩 → K = N + 3S
P=chaos-harness/jmeter/endpoints.jmx
PLAN=$P bash chaos-harness/jmeter/run.sh U02 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=district-counts
PLAN=$P bash chaos-harness/jmeter/run.sh U03 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=map-clusters
PLAN=$P bash chaos-harness/jmeter/run.sh U04 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=properties          # 반경 · 목록
PLAN=$P bash chaos-harness/jmeter/run.sh U05 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=property-detail
PLAN=$P bash chaos-harness/jmeter/run.sh U06 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=property-risk
PLAN=$P bash chaos-harness/jmeter/run.sh U07 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=wishlist            # 조회 · 등록/해제
PLAN=$P bash chaos-harness/jmeter/run.sh U08 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=loans-limit
PLAN=$P bash chaos-harness/jmeter/run.sh U09 - 'step(<N>,<K>,<S>,90s)' -- -Jtarget=notifications

# 단독 고정 부하 2분 (계획 변경 3 — U10 ~ U20, 한계는 보지 않는다)
PLAN=$P bash chaos-harness/jmeter/run.sh U10 - 'const(<N>,2m)' -- -Jtarget=ledger
PLAN=$P bash chaos-harness/jmeter/run.sh U11 - 'const(<N>,2m)' -- -Jtarget=registry
PLAN=$P bash chaos-harness/jmeter/run.sh U12 - 'const(<N>,2m)' -- -Jtarget=profile-read
PLAN=$P bash chaos-harness/jmeter/run.sh U13 - 'const(<N>,2m)' -- -Jtarget=subscriptions-read
PLAN=$P bash chaos-harness/jmeter/run.sh U14 - 'const(<N>,2m)' -- -Jtarget=reissue
PLAN=$P bash chaos-harness/jmeter/run.sh U15 - 'const(<N>,2m)' -- -Jtarget=profile-write
PLAN=$P bash chaos-harness/jmeter/run.sh U16 - 'const(<N>,2m)' -- -Jtarget=subscriptions-write
PLAN=$P bash chaos-harness/jmeter/run.sh U17 - 'const(<N>,2m)' -- -Jtarget=notification-read
PLAN=$P bash chaos-harness/jmeter/run.sh U18 - 'const(<N>,2m)' -- -Jtarget=notification-read-all
PLAN=$P bash chaos-harness/jmeter/run.sh U19 - 'const(<N>,2m)' -- -Jtarget=logout
PLAN=$P bash chaos-harness/jmeter/run.sh U20 - 'const(<N>,2m)' -- -Jtarget=stream-ticket

# 혼합(흔들림 R01 ~ R03 · 계단 R04) — 로그인을 섞는 방법은 3장 끝
PLAN=$P bash chaos-harness/jmeter/run.sh R01 - 'const(<N>,3m)' -- -Jtarget=mix
```

U10 ~ U20 의 순서는 쓰기 대상(U15 ~ U18)을 읽기 뒤에 둔 것이다. 이 차례는 계획 변경 3 의 표가 정하지 않았다. U19(`logout`)는 반복마다 짝 로그인이 BCrypt 를 돈다. 그래서 그 회차의 서버 자원 · DB 지표에는 로그인 몫이 섞인다. 응답 시간은 레이블(`auth/logout`)로 가른다.

다른 -J 를 더 줄 때도 `--` 뒤에 둔다(예: `-- -Jtarget=mix -Jaccounts=C:/…/endpoints.csv -Jproperty_ids=…`).

## 5. 확인하지 못한 것 · 알려 둘 것

- **JMeter 로 돌려 보지 않았다.** 로컬에 JMeter 가 없다(설치 금지). 확인한 것은 셋이다. ① XML 파싱 · 요소 짝(hashTree). ② Groovy 스크립트 다섯 개의 문법(Groovy 3.0 파서). ③ setUp · 고르기 · 돌려놓기 스크립트를 가짜 vars · props 로 4,000번 돌린 분포 — mix 비율, 60 : 40, 계정 빌리기 · 돌려놓기, 잘못된 target 이면 멈춤. JMeter 요소의 실제 동작은 로컬 스모크(확인 ①)에서 본다. 대상은 401 재시도 · BREAK_CURRENT_LOOP · Switch 이름 선택 · `__tstFeedback` 이다.
- **엔드포인트 통계에 준비 요청이 섞인다.** analyze 는 엔드포인트 통계를 URL 의 키로 묶는다. 그래서 `auth/login setup` 이 `auth/login` 으로, `notifications setup` 이 `notifications` 로 들어간다. 레이블 표는 갈린다. 회차 처음에 계정 수만큼(그리고 30분마다) 로그인이 나가므로 단독 회차에서 「가장 많이 부른 경로」는 바뀌지 않는다. 다만 회차 전체 요청 수 · 오류율에는 들어간다.
- **시험 계정은 관심 매물 · 알림이 거의 비어 있다.** `wishlist-read` · `notifications` · `notification-read(-all)` 은 빈 목록 경로를 잰다(인덱스 조회 한 번). 실사용자의 목록 길이를 재현하지 않는다.
- **`district-counts` 는 필터 없이 부른다.** 그래서 캐시 키가 하나다. 트래픽 정의서 3.2 가 1단계를 「캐시 적중 대상」으로 두었고, 필터 분포는 문서에 없다. 회차 대부분이 Redis 적중이고, DB 집계는 TTL(10분)마다 한 번이다.
- **지도 2단계의 영역은 그 구 전체다**(표본 좌표 2 · 98 백분위). 확대 · 이동 뒤 작은 영역의 분포는 문서에 없다.
- **혼합 회차에 로그인을 섞는 비율은 미확정이다.** 3장 끝을 본다.

## 6. E01 값 채우기

`queries/endpoints.yaml` 의 `${…}` 를 표본 첫 줄(top 묶음 · 가입 가능)과 그 구의 표시 영역으로 바꾼 사본을 만들어 explain.sh 에 준다. 영역 · 칸 · 반경 박스는 플랜 · 앱과 같은 식이다. 영역은 2 · 98 백분위이고 7자리 반올림, 칸은 영역 ÷ 12(`PropertyQueryService.GRID_DIVISIONS`), 반경 박스는 `GeoDistanceCalculator.boundingBox`(1 km)다.

```bash
python - <ACCOUNTS_DIR>/property-ids.csv chaos-harness/jmeter/queries/endpoints.yaml jmeter+001@rental.test > /tmp/endpoints.e01.yaml <<'EOF'
import csv, math, re, sys
from decimal import Decimal, ROUND_HALF_UP
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
r = next(x for x in rows if x["tier"] == "top" and x["insurance_eligible"] in ("t", "true"))
d = [x for x in rows if x["district"] == r["district"]]
def pct(xs, p):
    s = sorted(Decimal(v) for v in xs); return s[min(len(s) - 1, max(0, math.floor(p * (len(s) - 1))))]
q = lambda v: v.quantize(Decimal("0.0000001"), ROUND_HALF_UP)
mnla, mxla = q(pct([x["latitude"] for x in d], .02)), q(pct([x["latitude"] for x in d], .98))
mnlo, mxlo = q(pct([x["longitude"] for x in d], .02)), q(pct([x["longitude"] for x in d], .98))
lat, lng, R = float(r["latitude"]), float(r["longitude"]), 6371.0088
dla, dlo = math.degrees(1 / R), math.degrees(1 / (R * math.cos(math.radians(lat))))
v = {"PROPERTY_ID": r["property_id"], "DISTRICT": r["district"], "PROPERTY_TYPE": r["property_type"], "EMAIL": sys.argv[3],
     "MIN_LAT": str(mnla), "MAX_LAT": str(mxla), "MIN_LNG": str(mnlo), "MAX_LNG": str(mxlo),
     "CELL_LAT": repr((float(mxla) - float(mnla)) / 12), "CELL_LNG": repr((float(mxlo) - float(mnlo)) / 12),
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
