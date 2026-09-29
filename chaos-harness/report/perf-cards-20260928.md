# 성능 카드 (2026-09-28 · 29)

시험 계획서 7.3 양식. 이슈 #290 — **현재 구성에서 최적화할 지점을 찾는다.** 고치지 않았다 — 개선은 이 카드를 보고 따로 계획한다.

## 시험 조건

| 항목 | 값 |
| --- | --- |
| 대상 | 노드 넷 t3.small — APP-01(앞단 Nginx HTTPS · 슬롯 둘 · Redis · 수집기) · APP-02(슬롯 둘) · DB-01(primary) · DB-02(standby). 앱 이미지 `c84e33f` |
| 부하 생성기 | LOAD-01(10.20.10.50, IP별 상한 예외) → APP-01 사설 `https://10.20.0.10`, k6 v2.3.0, `chaos-harness/load/card.js`. 인증서 이름 검증 끔 |
| 계단 | 단계마다 75초 유지(첫 30초 워밍업 제외 → 45초 집계) · 휴지 30초. `RATES` 10 · 25 · 50 · 100(묶음 조회 · C 묶음은 50까지). think time 없음 |
| 앞단 상한 | 전체 상한 60 → **300 r/s 임시**(19:07:13 ~ 측정 끝, 되돌림은 아래 「되돌린 것」) |
| 데이터 | 매물 67,183 · 위험 분석 약 3.2만(최신) — 지금 규모 그대로 |
| 수집 | 회차마다 `collect-card.sh` — `pg_stat_statements` 초기화 · 앱 DB 테이블 통계 전후 · Redis INFO. 노드 CPU 는 Grafana(node exporter) 단계 구간 평균 |
| 토큰 | 시험 계정 loadtest+5101 ~ 5200(100개) — 회차마다 다시 로그인. 9/29 는 기존 구간이 로그인되지 않아(원인 미확인) 5201 ~ 5300 을 새로 가입해 썼다 — 시험 계정은 측정 뒤 모두 지웠다(「되돌린 것」) |
| 9/29 회차 | 같은 노드 · 이미지 · 데이터. 앞단 상한은 **풀지 않았다**(60 r/s) — 계단 10 · 25 · 50(반복 하나가 요청 둘이면 10 · 20 · 28, 깊은 페이지는 5 · 10). LOAD-01 을 새로 만들었다(같은 사설 주소). 켠 직후 `dnf-automatic` 을 먼저 돌리고(설치 없음) 측정 동안 타이머를 멈췄다 |
| 풀 · 스레드 지표 | Grafana 구간 질의 — 9/28 에 비었던 칸은 집계 스크립트 결함(「측정 경과 · 한계」)을 고쳐 9/28 회차까지 다시 뽑았다 |

## 핵심 발견 (먼저 읽을 것)

### 1. 준비문(prepared statement)의 일반 계획이 행마다 중첩 루프를 돈다 — **가장 큰 최적화 지점**

MyBatis 는 준비문으로 질의한다. PostgreSQL 은 같은 준비문을 5번 넘게 실행하면 **값과 무관한 일반 계획(generic plan)** 으로 바꿀 수 있다(`plan_cache_mode = auto` — 지금 값). 지도 묶음 조회에서 두 계획이 다르다(DB-01, 강남구 · 같은 영역).

| 계획 | 조인 방식 | 코드 테이블(`property_code`) 읽기 | 위험 분석 읽기 |
| --- | --- | --- | --- |
| 값을 넣은 계획(`EXPLAIN ANALYZE` 1회) | 해시 조인 | 1회(6행) | 전체 1회 해시 |
| **일반 계획**(`plan_cache_mode = force_generic_plan` + `EXPLAIN EXECUTE`) | **중첩 루프** | **결과 행마다 1회 — `loops=3977`** | 행마다 인덱스 1회 |

**많은 행을 읽는 질의에만 해당한다** — 목록(`LIMIT 21`)은 두 계획이 같고(중첩 루프 + Memoize, 0.3 ~ 0.4 ms) 읽는 행이 적어 문제가 없다. 실제 부하에서도 그랬다 — 묶음 조회 단독 약 9분(10 · 25 · 50 RPS, 요청 6,386)에 **`property_code` 전체 스캔 30,442,968회**(1억 1천만 행), `risk_analysis` 인덱스 스캔 11,956,869회. 행 6개짜리 표라 한 번은 싸지만 요청 하나에 수천 번이다. 같은 모양의 조인(`fromProperty` 조각 — 코드 테이블 둘 + 최신 위험 분석)이 목록 · 마커 · 상세에도 있다.

- 개선 후보(효과는 **미측정** — 고친 뒤 같은 카드로 잰다): ① 앱 DB 사용자에 `plan_cache_mode = force_custom_plan`(매번 값을 넣어 계획 — 계획 비용 수 ms 가 는다) ② JDBC `prepareThreshold` 조정 ③ 코드 조인 제거 — `code_value` 가 열거 상수명과 같으므로(매퍼 머리 주석) 앱이 `code_id` → 열거로 바꾸거나 매물 행에 코드 값을 둔다 ④ 아래 2번 설정 교정 뒤 일반 계획이 달라지는지 확인

### 2. DB 설정이 노드와 맞지 않는다

| 설정 | 지금 | 노드 | 영향 |
| --- | --- | --- | --- |
| `effective_cache_size` | **4GB** | DB 노드 메모리 2 GiB · postgres 컨테이너 상한 768m | 캐시를 실제보다 크게 믿어 인덱스 · 중첩 루프 계획을 싸게 본다 — 1번 계획 선택에 기여할 수 있다(추정) |
| `shared_buffers` | 64MB | 컨테이너 상한 768m | 작다 — 버퍼 적중은 OS 캐시에 기댄다 |
| `random_page_cost` | 4(HDD 기본) | EBS gp3(SSD) | SSD 에서는 흔히 낮춘다 — 값은 측정으로 |
| `max_connections` | 48 | 앱 풀 8 × 슬롯 4 = 32 + 백업 · 수집 | 여유 16 — 풀을 키우면 먼저 닿는다 |

### 3. APP-01 은 부하 전에 이미 메모리 여유가 거의 없다

측정 전(19:07 ~ 19:39, 부하 없음) APP-01 가용 메모리 **65 ~ 98 MB**. 앱 슬롯 둘이 약 1 GB(컨테이너 상한 600m 에 붙어 있다 — 474 ~ 544 MiB), 수집기 둘(prom-agent · vector)이 약 105 MB. 첫 묶음 조회 회차의 150 RPS 단계(19:48)에 **APP-01 이 메모리 고갈로 멈췄다**(load average 25, 가용 33 MB, SSH 무응답, HTTPS 는 2초대로 응답) → 19:56 재부팅(AWS reboot, IP 유지). 그 단계에 CPU 프로파일러(async-profiler)를 슬롯 JVM 에 붙이려 했다 — 9/29 에 확인하니 라이브러리가 컨테이너 안에 없어 **붙지 않는다**(「측정 경과 · 한계」). 프로파일러가 메모리를 쓴 것은 아니므로 멈춤은 150 RPS 부하 자체로 본다(**추정**). 그 뒤로는 프로파일러를 쓰지 않았고 가용 40 MB 미만이면 부하를 끊는 감시를 두었다.

- 재부팅 뒤 **APP-01 의 슬롯 `app-1` 이 다시 뜨지 않았다**(`Exited (143)`, 재시작 정책 `unless-stopped`) — 10분 넘게 슬롯 셋으로 돌았고 손으로 `docker compose up -d app-1`. 원인 **미확인**
- 9/26 사고 ① · ②(APP-01 멈춤)와 같은 자원이다. 입구 노드가 앱 슬롯 · Redis · 수집기 · NAT 를 겸하는 구성의 한계 — 부하 시험 결과(용량 산정 리포트)의 「가장 여유가 적은 자원은 APP-01 메모리」와 같은 결론

### 4. 로그인은 비밀번호 해싱을 트랜잭션 안에서 해 CPU 포화가 커넥션 고갈로 번진다

`UserCommandService.login` 이 `@Transactional` 안에서 `passwordEncoder.matches`(BCrypt 기본 강도 10)를 부른다 — 해싱하는 동안 DB 커넥션을 쥐고 있다. 로그인 단독 **25 ~ 50 RPS 사이에서 포화**(50 에서 p95 9.7 s · 5xx 11 % · 앱 노드 CPU 0.92 · 0.93, Hikari 대기 최대 164 · Tomcat 사용 200 = 상한). 포화 시점 스레드 덤프(APP-02 슬롯 하나, 요청 스레드 52): **BCrypt 계산 중 8(= 풀 크기 8 을 모두 쥔 채) · 커넥션 대기 28**. 로그인 한 건의 앱 CPU 는 약 80 ms(추정 — 25 RPS 단계 두 앱 노드 CPU ÷ 처리량).

- 해싱이 비싼 것은 의도다(강도는 보안 · 암호화 설계서가 정한다 — 여기서 낮추자는 것이 아니다). 문제는 **해싱 동안 커넥션을 쥐는 것** — 로그인이 몰리면 커넥션을 쓰는 다른 모든 경로가 같이 멈춘다
- 개선 후보(미측정): 사용자 · 해시 조회 → 트랜잭션 밖에서 `matches` → 짧은 트랜잭션으로 `last_login_at` 갱신

### 5. 지도 묶음 조회는 매물이 몰린 구에서 10 ~ 25 RPS 에 무너진다

같은 묶음 조회를 요청 분포만 바꿔 쟀다.

| 분포 | 25 RPS p95 | 50 RPS | DB-01 CPU(50) | 요청당 DB 시간 |
|---|---|---|---|---|
| 쏠림 — 매물 수 1위 구 하나(9/29) | **1,922 ms** | 타임아웃 94 % | 0.85 | 664 ms |
| 기본 — 상위 3구 60 %(9/28) | 69 ms | p95 3,254 ms | 0.89 | 318 ms |
| 균등 — 25개 구(9/29) | 47 ms | p95 78 ms | 0.46 | 21 ms |

비용이 **영역 안 매물 수에 비례**한다(핵심 발견 1 의 행마다 도는 코드 테이블 조인 — 쏠림 회차 `property_code` 전체 스캔 4,449만 회). 목표 18 RPS 는 균등 · 기본 분포에서는 넘지만 **쏠림에서는 한계(10 ~ 25 사이)가 목표 근처**다. 포화 시점 스레드 덤프: 커넥션 대기 29 · 질의 실행 7 — DB 가 느려 풀이 막힌다. 마커(반경)도 쏠림에서 응답 122 KB · 50 RPS p99 524 ms(균등 58 KB · 90 ms).

### 6. 외래 키 셋에 인덱스가 없어 부모 행을 지울 때마다 자식 표를 전체 스캔한다

`property_notification.subscription_id` · `rate_notification.subscription_id`(`ON DELETE SET NULL`, V13) · `wishlist_notification.wish_id` — 인덱스가 없다(`wishlist_notification` 은 `notif_id` 만, V14). 구독 설정 저장은 **같은 값이어도 전부 지우고 다시 넣어** 지울 때마다 두 알림 표를 전체 스캔했다(C-subs2 17,508회). 관심 해제도 `wishlist_notification` 전체 스캔(3,182회). **지금은 자식 표가 비어 있어(읽은 행 0) 비용이 0** — 알림 이력이 쌓이면 이력 크기에 비례한다.

- 개선 후보: 세 FK 에 인덱스, 구독 저장은 바뀐 것만 반영

### 7. 같은 것을 요청마다 다시 읽는다 — 대출 한도 · 위험 판정

대출 한도는 **요청당 질의 19.9회** — 같은 매물을 4번(25,716 ÷ 6,429) 읽고 기준 표 9개(보증 · 보험 · 대출 상품 · 규제 · 위험 기준)를 매번 전체 읽는다. 위험 판정 조회는 요청당 17회(같은 매물 3회 · 판정 기준 표). 둘 다 50 RPS(위험 판정은 100)까지 p95 70 ms 안이라 **지금 한계는 아니다** — 요청마다 앱 CPU 와 커넥션 점유 시간이 는다. 기준 표는 관리자가 바꿀 때만 바뀐다.

- 개선 후보: 기준 표 캐시(변경 시 무효화), 매물 한 번 읽어 넘기기

## 요약판

목표 = 트래픽 정의서 3장 비중 × 150 RPS(비중이 없는 경로는 —). 판정 기준은 시험 계획서 7.3(**잠정**). 「단독 한계」는 계단에서 p95 500 ms 또는 5xx 1 % 를 처음 넘은 단계. 9/29 회차는 앞단 상한 안(50 RPS 까지)만 쟀다 — 「기준 안」은 그 단계까지다.

| 엔드포인트 | 비중 · 목표 | 단독 한계 | 먼저 닿는 자원 | 요청당 질의 | 판정 | 개선 후보 |
|---|---|---|---|---|---|---|
| 지도 묶음 `map-clusters` | 12 % · 18 RPS | 기본 25 ~ 50 · **쏠림 10 ~ 25** · 균등 50 까지 기준 안 | **DB-01 CPU 0.85 ~ 0.89** → 커넥션 풀(대기 61 ~ 164) | 1.6 ~ 1.9 | **개선 필요** — 코드 테이블 전체 스캔(핵심 발견 1 · 5) | 일반 계획 교정 · 결과 캐시 · 사전 집계 |
| 지도 마커(반경) | 9 % · 13.5 RPS | 50 ~ 100 사이(100 에서 5xx 66 %) | 커넥션 풀(대기 124) · APP-01 CPU 0.8 · 앞단 Nginx 메모리 | 0.9 | **개선 필요** — 코드 테이블 전체 스캔, 응답 75 ~ 122 KB | 일반 계획 교정 · 응답 크기 · 앞단 버퍼 |
| 자치구 수 | 31 % · 46.5 RPS | 100 까지 기준 안(p95 8 ms) | — | 0.01 | 건강 — Redis 적중 99.98 % | — |
| 매물 상세 | 13 % · 19.5 RPS | 100 까지 기준 안(p95 11 ms) | — | 1.0 | 건강 | — |
| 위험 판정 조회 | 10 % · 15 RPS | 100 까지 기준 안(p95 58 ms) | 앱(응답 34 ms 중 DB 0.5 ms) — 50 RPS 스레드 덤프는 유휴 | **17** | **주의** — 같은 매물 3회 · 기준 표 전체 읽기(핵심 발견 7) | 기준 표 캐시 · 매물 한 번 읽기 |
| 목록 첫 페이지 · 깊은 페이지 | 7 % · 10.5 RPS | 100 까지 기준 안(p95 13 ms) · 깊은 페이지 초당 50쪽 p95 15 ms | — | 1.0 | 건강 | — |
| 알림 전체 읽음 | — | 50 까지 기준 안(한 계정 경합도 p95 7 ms) | — | 1.0 | 건강 | — |
| 프로필 저장(같은 값) | — | 반복 28/s(요청 56/s) 까지 기준 안 · 경합 같음 | — | 2.5 | 건강 | — |
| 구독 설정 저장(같은 값) | — | 반복 28/s 까지 기준 안(p95 19 ms) | — | 4.5 | **주의** — 전부 지우고 다시 넣기 · FK 인덱스 없음(핵심 발견 6) | 바뀐 것만 반영 · FK 인덱스 |
| 관심 목록 | — | 50 까지 기준 안(p95 18 ms) | — | 1.0 | 건강(질의 한 건 평균 5.1 ms — 최신 위험 분석 조인) | — |
| 알림 목록 | — | 50 까지 기준 안(p95 9 ms) | — | 2.0 | 건강 | — |
| 대출 한도 | — | 50 까지 기준 안(p95 45 ms) | 앱 | **19.9** | **주의** — 매물 4회 · 기준 표 9개 매번(핵심 발견 7) | 기준 표 캐시 |
| 등기 · 건축물대장 | — | 50 까지 기준 안(p95 14 · 16 ms) | — | 5.0 · 3.0 | 건강 | — |
| 토큰 재발급 | — | 50 까지 기준 안(p95 13 ms) | — | 1.0 | 건강 | — |
| 관심 등록 · 해제 | — | 50 까지 기준 안(p95 11 ms) | — | 3.0 | 주의 — 해제마다 FK 인덱스 없는 표 전체 스캔(지금 0행) | FK 인덱스 |
| 재분석 | — | 50 까지 기준 안(p95 12 ~ 56 ms) | — | 3.6 | 건강 — 대부분 10분 간격 429(실제 분석 약 950회) | — |
| **로그인** | — | **25 ~ 50 사이**(50 에서 p95 9.7 s · 5xx 11 %) | **앱 CPU 0.92**(BCrypt) → 커넥션 풀(대기 164) | 2.9 | **개선 필요** — 해싱 동안 커넥션 점유(핵심 발견 4) | 해싱을 트랜잭션 밖으로 |

## 엔드포인트별 카드

카드 순서: A 핵심 조회(9/28 · 100 RPS 까지) → 분포 변형(9/29) → C 파급 경로 → B 묶음(9/29). 「상위 질의」 · 「전체 스캔」에는 k6 setup 이 부르는 묶음 조회 25건(`calls=25`, 평균 약 24 ms)과 그 몫의 `property_code` 전체 스캔 약 13만 4천 회가 회차마다 섞인다 — 경로 자체의 값은 그만큼 뺀다.

### GET /api/properties/map-clusters (지도 2단계 묶음)

트래픽 비중 12 % → 목표 18.0 RPS. 요청 6,386 · dropped 95 · 요청당 질의 1.83회 · 요청당 DB 실행 318.1 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 34 | 62 | 68 | 0.0 % | 9.9 | 0.18 | 0.04 | 0.14 | 0 | 4 |
| 25 | 25.0 | 33 | 69 | 82 | 0.0 % | 10.2 | 0.14 | 0.05 | 0.32 | 0 | 5 |
| 50 | 48.6 | 1595 | 3254 | 3843 | 0.0 % | 10.0 | 0.21 | 0.09 | 0.89 | 61 | 97 |

- 단독 한계: **50 RPS 에서 기준 초과**(p95 3254 ms · 5xx 0.0 %)
- 상위 질의(실행 시간 합 순):
  - 호출 2779 · 평균 439.779 ms · 행 137984 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 2371 · 평균 262.170 ms · 행 113787 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 1210 · 평균 152.587 ms · 행 32640 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
- 전체 스캔(회차 중 증가): property_code 30,442,968회(110,120,640행) · risk_analysis 1회(32,064행)
- 인덱스 스캔 상위: risk_analysis 11,956,869회 · property 25,983회

### GET /api/properties — 좌표 반경(지도 3단계 마커)

트래픽 비중 9 % → 목표 13.5 RPS. 요청 11,610 · dropped 805 · 요청당 질의 0.92회 · 요청당 DB 실행 61.4 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 24 | 55 | 72 | 0.0 % | 75.0 | 0.13 | 0.06 | 0.07 | 0 | 4 |
| 25 | 25.0 | 28 | 58 | 90 | 0.0 % | 90.1 | 0.19 | 0.11 | 0.14 | 0 | 6 |
| 50 | 50.0 | 31 | 72 | 116 | 0.0 % | 96.6 | 0.40 | 0.23 | 0.25 | 0 | 7 |
| 100 | 56.1 | 2144 | 9999 | 10000 | 65.7 % | 7.2 | 0.80 | 0.19 | 0.50 | 124 | 150 |

- 단독 한계: **100 RPS 에서 기준 초과**(p95 9999 ms · 5xx 65.7 %)
- 상위 질의(실행 시간 합 순):
  - 호출 732 · 평균 599.356 ms · 행 36073 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 280 · 평균 381.463 ms · 행 13410 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 4746 · 평균 18.081 ms · 행 3449033 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 15,933,474회(55,734,488행) · risk_analysis 2회(64,128행)
- 인덱스 스캔 상위: risk_analysis 6,728,076회 · mortgage_history 2,978,404회

### GET /api/properties/district-counts (자치구 수 · 캐시)

트래픽 비중 31 % → 목표 46.5 RPS. 요청 14,055 · dropped 0 · 요청당 질의 0.01회 · 요청당 DB 실행 0.1 ms · Redis 적중/실패 14002/3

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 8 | 12 | 18 | 0.0 % | 2.5 | 0.49 | 0.06 | 0.02 | 0 | 5 |
| 25 | 25.0 | 6 | 9 | 16 | 0.0 % | 2.5 | 0.14 | 0.04 | 0.02 | 0 | 4 |
| 50 | 50.0 | 5 | 7 | 14 | 0.0 % | 2.5 | 0.17 | 0.05 | 0.02 | 0 | 5 |
| 100 | 100.0 | 5 | 8 | 22 | 0.0 % | 2.5 | 0.23 | 0.09 | 0.01 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.082 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 1 · 평균 69.952 ms · 행 25 — `SELECT p.district AS name; COUNT(*) AS total_count; SUM(CASE WHEN ra.risk_grade = $1 THEN $2 ELSE $3 END) AS safe_count;…`
  - 호출 1 · 평균 35.220 ms · 행 25 — `SELECT p.district AS name; COUNT(*) AS total_count; SUM(CASE WHEN ra.risk_grade = $2 THEN $3 ELSE $4 END) AS safe_count;…`
- 전체 스캔(회차 중 증가): property 4회(201,549행) · property_code 134,380회(412,071행) · risk_analysis 4회(128,256행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · property 233회

### GET /api/properties/{id} (매물 상세)

트래픽 비중 13 % → 목표 19.5 RPS. 요청 14,055 · dropped 0 · 요청당 질의 1.00회 · 요청당 DB 실행 0.1 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 7 | 11 | 25 | 0.0 % | 0.8 | 0.11 | 0.04 | 0.03 | 0 | 4 |
| 25 | 25.0 | 6 | 9 | 10 | 0.0 % | 0.8 | 0.09 | 0.04 | 0.04 | 0 | 5 |
| 50 | 50.0 | 6 | 9 | 19 | 0.0 % | 0.8 | 0.12 | 0.06 | 0.03 | 0 | 7 |
| 100 | 100.0 | 6 | 11 | 22 | 0.0 % | 0.8 | 0.19 | 0.08 | 0.06 | 0 | 6 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 14004 · 평균 0.107 ms · 행 14004 — `SELECT p.property_id; p.district; p.address; p.latitude; p.longitude; pt.code_value AS property_type; ct.code_value AS c…`
  - 호출 25 · 평균 25.461 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 25 · 평균 0.568 ms · 행 2525 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
- 전체 스캔(회차 중 증가): property_code 162,282회(510,545행)
- 인덱스 스캔 상위: risk_analysis 83,706회 · property 14,185회

### GET /api/properties/{id}/risk (위험 판정 조회)

트래픽 비중 10 % → 목표 15.0 RPS. 요청 14,055 · dropped 0 · 요청당 질의 17.00회 · 요청당 DB 실행 0.5 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 42 | 69 | 87 | 0.0 % | 1.4 | 0.28 | 0.20 | 0.06 | 0 | 5 |
| 25 | 25.0 | 36 | 46 | 65 | 0.0 % | 1.4 | 0.22 | 0.13 | 0.05 | 0 | 5 |
| 50 | 50.0 | 35 | 61 | 196 | 0.0 % | 1.4 | 0.25 | 0.19 | 0.09 | 0 | 6 |
| 100 | 100.0 | 34 | 58 | 486 | 0.0 % | 1.4 | 0.35 | 0.25 | 0.12 | 0 | 8 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 42012 · 평균 0.038 ms · 행 42012 — `select p1_0.property_id;p1_0.address;p1_0.area_sqm;p1_0.built_year;p1_0.contract_type_code_id;p1_0.registered_at;p1_0.de…`
  - 호출 14004 · 평균 0.049 ms · 행 29710 — `select oh1_0.ownership_id;oh1_0.auction_yn;oh1_0.recorded_at;oh1_0.is_current;oh1_0.lease_registration_yn;oh1_0.owner_na…`
  - 호출 14004 · 평균 0.045 ms · 행 13708 — `select br1_0.registry_id from building_registry br1_0 where br1_0.property_id=$1 fetch first $2 rows only…`
- 전체 스캔(회차 중 증가): guarantee_criteria 14,061회(41,942행) · guarantee_premium_rate 13,935회(167,220행) · hf_criteria 13,935회(13,935행) · insurance_product 13,935회(41,805행) · property_code 148,899회(499,185행) · risk_criteria 13,935회(13,935행) · sgi_criteria 13,935회(13,935행)
- 인덱스 스캔 상위: risk_analysis 83,644회 · property 43,053회

### GET /api/properties — 목록 첫 페이지

트래픽 비중 7 % → 목표 10.5 RPS. 요청 14,055 · dropped 0 · 요청당 질의 1.00회 · 요청당 DB 실행 0.2 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 11 | 27 | 40 | 0.0 % | 6.8 | 0.16 | 0.62 | 0.04 | 0 | 4 |
| 25 | 25.0 | 9 | 16 | 29 | 0.0 % | 6.8 | 0.10 | 0.09 | 0.03 | 0 | 4 |
| 50 | 50.0 | 9 | 15 | 32 | 0.0 % | 6.8 | 0.14 | 0.12 | 0.05 | 0 | 5 |
| 100 | 100.0 | 9 | 13 | 19 | 0.0 % | 6.8 | 0.22 | 0.17 | 0.08 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 8428 · 평균 0.174 ms · 행 178988 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
  - 호출 4201 · 평균 0.190 ms · 행 88221 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
  - 호출 25 · 평균 23.832 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
- 전체 스캔(회차 중 증가): flyway_schema_history 2회(30행) · property 2회(67,183행) · property_code 147,078회(479,610행) · risk_analysis 2회(64,720행)
- 인덱스 스캔 상위: risk_analysis 362,743회 · property 70,160회

### 묶음 조회 — 쏠림(매물 수 1위 구 하나)

트래픽 비중 12 % → 목표 18.0 RPS. 요청 6,014 · dropped 467 · 요청당 질의 1.64회 · 요청당 DB 실행 663.7 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 56 | 69 | 86 | 0.0 % | 12.5 | 0.08 | 0.04 | 0.23 | 0 | 4 |
| 25 | 24.5 | 810 | 1922 | 2713 | 0.0 % | 12.6 | 0.10 | 0.06 | 0.83 | 14 | 50 |
| 50 | 46.1 | 10000 | 10001 | 10001 | 93.8 % | 1.2 | 0.15 | 0.09 | 0.85 | 164 | 200 |

- 단독 한계: **25 RPS 에서 기준 초과**(p95 1922 ms · 5xx 0.0 %)
- 상위 질의(실행 시간 합 순):
  - 호출 3449 · 평균 862.409 ms · 행 197779 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 1555 · 평균 564.030 ms · 행 86771 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $23) AS latitude; ROUND(AVG(g.longitude); $24…`
  - 호출 240 · 평균 575.210 ms · 행 9967 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $24) AS latitude; ROUND(AVG(g.longitude); $25…`
- 전체 스캔(회차 중 증가): property_code 44,485,614회(154,122,926행)
- 인덱스 스캔 상위: risk_analysis 19,506,982회 · property 20,995회

### 묶음 조회 — 균등(25개 구)

트래픽 비중 12 % → 목표 18.0 RPS. 요청 6,480 · dropped 0 · 요청당 질의 1.85회 · 요청당 DB 실행 20.8 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 24 | 52 | 68 | 0.0 % | 8.6 | 0.10 | 0.03 | 0.11 | 0 | 4 |
| 25 | 25.0 | 23 | 47 | 67 | 0.0 % | 8.5 | 0.11 | 0.05 | 0.20 | 0 | 5 |
| 50 | 50.0 | 26 | 78 | 129 | 0.0 % | 8.4 | 0.21 | 0.09 | 0.46 | 0 | 6 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 3347 · 평균 27.713 ms · 행 143200 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 1548 · 평균 17.624 ms · 행 61764 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 1559 · 평균 8.683 ms · 행 37104 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 21,583,539회(75,854,594행) · risk_analysis 2회(64,722행)
- 인덱스 스캔 상위: risk_analysis 8,914,110회 · property 23,117회

### 마커 — 쏠림(1위 구 하나)

트래픽 비중 9 % → 목표 13.5 RPS. 요청 6,480 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 14.7 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 31 | 71 | 107 | 0.0 % | 101.1 | 0.14 | 0.10 | 0.09 | 0 | 4 |
| 25 | 25.0 | 32 | 82 | 111 | 0.0 % | 119.1 | 0.26 | 0.22 | 0.16 | 0 | 5 |
| 50 | 50.0 | 41 | 129 | 524 | 0.0 % | 122.3 | 0.50 | 0.44 | 0.33 | 0 | 8 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 4041 · 평균 15.856 ms · 행 3293043 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
  - 호출 1309 · 평균 13.191 ms · 행 415138 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
  - 호출 1052 · 평균 11.670 ms · 행 278700 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
- 전체 스캔(회차 중 증가): property_code 9,804,988회(35,121,701행)
- 인덱스 스캔 상위: risk_analysis 4,137,486회 · mortgage_history 3,964,277회

### 마커 — 균등(25개 구)

트래픽 비중 9 % → 목표 13.5 RPS. 요청 6,481 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 6.2 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 18 | 35 | 52 | 0.0 % | 44.8 | 0.10 | 0.05 | 0.06 | 0 | 4 |
| 25 | 25.0 | 18 | 40 | 61 | 0.0 % | 55.6 | 0.14 | 0.12 | 0.09 | 0 | 4 |
| 50 | 50.0 | 18 | 57 | 90 | 0.0 % | 57.6 | 0.26 | 0.24 | 0.18 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 2027 · 평균 8.025 ms · 행 1218934 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
  - 호출 2570 · 평균 5.783 ms · 행 524772 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
  - 호출 1805 · 평균 4.239 ms · 행 145067 — `SELECT p.property_id; p.latitude; p.longitude; p.deposit; ra.risk_grade; ct.code_value AS contract_type; COALESCE(p.mont…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 5,828,169회(22,868,037행) · risk_analysis 2회(64,722행)
- 인덱스 스캔 상위: risk_analysis 1,981,986회 · mortgage_history 1,039,515회

### 목록 깊은 페이지(반복 하나 = 5페이지)

트래픽 비중 7 % → 목표 10.5 RPS. 요청 5,711 · dropped 0 · 요청당 질의 1.00회 · 요청당 DB 실행 0.3 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5 | 25.0 | 9 | 15 | 61 | 0.0 % | 6.8 | 0.12 | 0.05 | 0.06 | 0 | 4 |
| 10 | 50.0 | 9 | 15 | 27 | 0.0 % | 6.8 | 0.15 | 0.10 | 0.06 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 4528 · 평균 0.193 ms · 행 95088 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
  - 호출 25 · 평균 34.605 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 1157 · 평균 0.201 ms · 행 26297 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
- 전체 스캔(회차 중 증가): property_code 141,294회(439,449행)
- 인덱스 스캔 상위: risk_analysis 188,149회 · property 28,649회

### PATCH /api/notifications/read-all (알림 전체 읽음)

파급 경로(비중 없음). 요청 6,480 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 0.1 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 7 | 11 | 12 | 0.0 % | 0.3 | 0.09 | 0.05 | 0.03 | 0 | 4 |
| 25 | 25.0 | 6 | 10 | 16 | 0.0 % | 0.3 | 0.08 | 0.05 | 0.02 | 0 | 4 |
| 50 | 50.0 | 6 | 9 | 16 | 0.0 % | 0.3 | 0.12 | 0.08 | 0.03 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.751 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 6429 · 평균 0.016 ms · 행 0 — `update notification n1_0 set is_read=$2 where n1_0.user_id=$1 and n1_0.is_read=$3…`
  - 호출 25 · 평균 0.583 ms · 행 2525 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
- 전체 스캔(회차 중 증가): property_code 134,372회(412,023행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · notification 6,395회

### 알림 전체 읽음 — 경합(모든 요청이 한 계정)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 0.1 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 6 | 8 | 18 | 0.0 % | 0.3 | 0.07 | 0.02 | 0.03 | 0 | 4 |
| 25 | 25.0 | 5 | 8 | 25 | 0.0 % | 0.3 | 0.08 | 0.03 | 0.02 | 0 | 4 |
| 50 | 50.0 | 5 | 7 | 13 | 0.0 % | 0.3 | 0.11 | 0.04 | 0.04 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.925 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 6430 · 평균 0.017 ms · 행 0 — `update notification n1_0 set is_read=$2 where n1_0.user_id=$1 and n1_0.is_read=$3…`
  - 호출 25 · 평균 0.601 ms · 행 2525 — `SELECT p.property_id; p.district; p.address; pt.code_value AS property_type; ct.code_value AS contract_type; p.deposit; …`
- 전체 스캔(회차 중 증가): property_code 134,372회(412,023행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · notification 6,402회

### GET → PUT /api/me/profile (같은 값 저장 · 9/29 재측정)

파급 경로(비중 없음). 요청 8,829 · dropped 0 · 요청당 질의 2.50회 · 요청당 DB 실행 0.2 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 20.0 | 7 | 12 | 17 | 0.0 % | 0.6 | 0.08 | 0.04 | 0.03 | 0 | 4 |
| 20 | 40.0 | 7 | 11 | 23 | 0.0 % | 0.6 | 0.08 | 0.04 | 0.04 | 0 | 4 |
| 28 | 56.0 | 7 | 10 | 15 | 0.0 % | 0.6 | 0.10 | 0.06 | 0.04 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.194 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 8778 · 평균 0.055 ms · 행 8778 — `SELECT u.name; u.phone; u.email; u.role; u.created_at AT TIME ZONE $2 AS created_at FROM users u WHERE u.user_id = $1 AN…`
  - 호출 8778 · 평균 0.031 ms · 행 8778 — `SELECT u.annual_income; u.credit_score; u.existing_loan; u.existing_loan_annual_payment; u.has_house; u.own_fund FROM us…`
- 전체 스캔(회차 중 증가): property_code 134,372회(412,023행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · users 21,892회

### 프로필 저장 — 경합(모든 요청이 한 계정)

파급 경로(비중 없음). 요청 8,829 · dropped 0 · 요청당 질의 2.50회 · 요청당 DB 실행 0.2 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 20.0 | 7 | 12 | 20 | 0.0 % | 0.6 | 0.10 | 0.03 | 0.03 | 0 | 4 |
| 20 | 40.0 | 6 | 11 | 19 | 0.0 % | 0.6 | 0.09 | 0.04 | 0.04 | 0 | 4 |
| 28 | 56.0 | 7 | 11 | 17 | 0.0 % | 0.6 | 0.11 | 0.05 | 0.04 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.204 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 8778 · 평균 0.055 ms · 행 8778 — `SELECT u.name; u.phone; u.email; u.role; u.created_at AT TIME ZONE $2 AS created_at FROM users u WHERE u.user_id = $1 AN…`
  - 호출 8778 · 평균 0.031 ms · 행 8778 — `SELECT u.annual_income; u.credit_score; u.existing_loan; u.existing_loan_annual_payment; u.has_house; u.own_fund FROM us…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,376회(412,047행) · risk_analysis 2회(64,722행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · users 21,889회

### GET → PUT /api/me/notification-subscriptions (같은 값 저장 · 9/29)

파급 경로(비중 없음). 요청 8,829 · dropped 0 · 요청당 질의 4.48회 · 요청당 DB 실행 0.5 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 20.0 | 10 | 20 | 27 | 0.0 % | 0.5 | 0.09 | 0.05 | 0.06 | 0 | 4 |
| 20 | 40.0 | 10 | 21 | 27 | 0.0 % | 0.5 | 0.10 | 0.06 | 0.06 | 0 | 5 |
| 28 | 56.0 | 9 | 19 | 24 | 0.0 % | 0.5 | 0.11 | 0.06 | 0.07 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 17556 · 평균 0.092 ms · 행 17556 — `insert into notification_subscription (is_active;contract_type;created_at;deposit_max;deposit_min;subscription_type;targ…`
  - 호출 25 · 평균 24.100 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 4389 · 평균 0.112 ms · 행 17556 — `delete from notification_subscription ns1_0 where ns1_0.user_id=$1…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,376회(412,047행) · property_notification 17,508회(0행) · rate_notification 17,508회(0행) · risk_analysis 2회(64,722행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · users 21,885회

### GET /api/me/wishlist (관심 목록)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 5.2 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 12 | 16 | 19 | 0.0 % | 1.0 | 0.07 | 0.05 | 0.05 | 0 | 5 |
| 25 | 25.0 | 11 | 17 | 26 | 0.0 % | 1.0 | 0.07 | 0.05 | 0.09 | 0 | 5 |
| 50 | 50.0 | 11 | 18 | 43 | 0.0 % | 1.0 | 0.12 | 0.09 | 0.14 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 6430 · 평균 5.141 ms · 행 32150 — `SELECT w.wish_id; p.property_id; p.district; p.deposit; ra.risk_grade; ra.previous_grade; w.created_at AT TIME ZONE $3 A…`
  - 호출 25 · 평균 24.811 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 1 · 평균 72.056 ms · 행 25 — `SELECT p.district AS name; COUNT(*) AS total_count; SUM(CASE WHEN ra.risk_grade = $1 THEN $2 ELSE $3 END) AS safe_count;…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,376회(412,047행) · risk_analysis 2회(64,720행)
- 인덱스 스캔 상위: risk_analysis 101,739회 · property 32,499회

### GET /api/notifications (알림 목록)

파급 경로(비중 없음). 요청 6,480 · dropped 0 · 요청당 질의 2.00회 · 요청당 DB 실행 0.2 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 7 | 10 | 17 | 0.0 % | 0.3 | 0.08 | 0.04 | 0.04 | 0 | 4 |
| 25 | 25.0 | 7 | 10 | 15 | 0.0 % | 0.3 | 0.07 | 0.04 | 0.02 | 0 | 4 |
| 50 | 50.0 | 7 | 9 | 15 | 0.0 % | 0.3 | 0.13 | 0.07 | 0.03 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.608 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 6429 · 평균 0.047 ms · 행 0 — `SELECT n.notif_id AS notification_id; n.notif_type AS type; wn.property_id; wn.before_value; wn.after_value; n.is_read A…`
  - 호출 6429 · 평균 0.017 ms · 행 6429 — `SELECT count(*) FROM notification WHERE user_id = $1 AND is_read = $2…`
- 전체 스캔(회차 중 증가): property_code 134,372회(412,023행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · notification 12,798회

### GET /api/loans/limit (대출 한도)

파급 경로(비중 없음). 요청 6,480 · dropped 0 · 요청당 질의 19.85회 · 요청당 DB 실행 0.9 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 40 | 59 | 81 | 0.0 % | 0.5 | 0.20 | 0.17 | 0.05 | 0 | 4 |
| 25 | 25.0 | 36 | 50 | 82 | 0.0 % | 0.5 | 0.16 | 0.15 | 0.07 | 0 | 7 |
| 50 | 50.0 | 34 | 45 | 52 | 0.0 % | 0.5 | 0.24 | 0.22 | 0.14 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25716 · 평균 0.050 ms · 행 25716 — `select p1_0.property_id;p1_0.address;p1_0.area_sqm;p1_0.built_year;p1_0.contract_type_code_id;p1_0.registered_at;p1_0.de…`
  - 호출 25 · 평균 23.856 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 6429 · 평균 0.075 ms · 행 13170 — `select oh1_0.ownership_id;oh1_0.auction_yn;oh1_0.recorded_at;oh1_0.is_current;oh1_0.lease_registration_yn;oh1_0.owner_na…`
- 전체 스캔(회차 중 증가): guarantee_criteria 6,387회(19,161행) · guarantee_premium_rate 6,387회(76,644행) · hf_criteria 6,387회(6,387행) · insurance_product 6,387회(19,161행) · loan_product 6,387회(6,387행) · loan_regulation 6,387회(6,387행) · property 2회(67,183행) · property_code 140,763회(450,369행) · risk_analysis 2회(64,720행) · risk_criteria 6,387회(6,387행) · sgi_criteria 6,387회(6,387행)
- 인덱스 스캔 상위: risk_analysis 76,096회 · property 25,796회

### GET /api/properties/{id}/registry (등기)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 4.97회 · 요청당 DB 실행 0.4 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 12 | 15 | 33 | 0.0 % | 0.8 | 0.07 | 0.04 | 0.04 | 0 | 4 |
| 25 | 25.0 | 11 | 14 | 18 | 0.0 % | 0.8 | 0.07 | 0.04 | 0.04 | 0 | 5 |
| 50 | 50.0 | 11 | 14 | 24 | 0.0 % | 0.8 | 0.10 | 0.06 | 0.05 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.043 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 6430 · 평균 0.080 ms · 행 13687 — `SELECT oh.is_current AS is_active; oh.registration_cause AS cause; oh.ownership_date AS received_date; oh.owner_name AS …`
  - 호출 6430 · 평균 0.065 ms · 행 6430 — `select p1_0.property_id;p1_0.address;p1_0.area_sqm;p1_0.built_year;p1_0.contract_type_code_id;p1_0.registered_at;p1_0.de…`
- 전체 스캔(회차 중 증가): property_code 134,374회(412,035행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · building_registry 26,087회

### GET /api/properties/{id}/ledger (건축물대장)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 2.99회 · 요청당 DB 실행 0.3 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 10 | 17 | 26 | 0.0 % | 0.5 | 0.07 | 0.05 | 0.05 | 0 | 4 |
| 25 | 25.0 | 9 | 14 | 25 | 0.0 % | 0.5 | 0.07 | 0.04 | 0.03 | 0 | 4 |
| 50 | 50.0 | 9 | 16 | 22 | 0.0 % | 0.5 | 0.10 | 0.07 | 0.05 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.758 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 6430 · 평균 0.067 ms · 행 6430 — `select p1_0.property_id;p1_0.address;p1_0.area_sqm;p1_0.built_year;p1_0.contract_type_code_id;p1_0.registered_at;p1_0.de…`
  - 호출 6430 · 평균 0.053 ms · 행 6428 — `select bl1_0.ledger_id from building_ledger bl1_0 where bl1_0.property_id=$1 fetch first $2 rows only…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,378회(412,059행) · risk_analysis 2회(64,720행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · building_ledger 12,801회

### POST /api/auth/reissue (토큰 재발급)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 1.01회 · 요청당 DB 실행 0.2 ms · Redis 적중/실패 6430/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 10 | 14 | 26 | 0.0 % | 0.8 | 0.07 | 0.04 | 0.03 | 0 | 4 |
| 25 | 25.0 | 9 | 14 | 31 | 0.0 % | 0.8 | 0.08 | 0.04 | 0.02 | 0 | 5 |
| 50 | 50.0 | 9 | 13 | 23 | 0.0 % | 0.8 | 0.12 | 0.07 | 0.03 | 0 | 5 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 23.799 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 6430 · 평균 0.062 ms · 행 6430 — `select u1_0.user_id;u1_0.annual_income;u1_0.created_at;u1_0.credit_score;u1_0.deleted_at;u1_0.email;u1_0.existing_loan;u…`
  - 호출 1 · 평균 72.001 ms · 행 25 — `SELECT p.district AS name; COUNT(*) AS total_count; SUM(CASE WHEN ra.risk_grade = $1 THEN $2 ELSE $3 END) AS safe_count;…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,376회(412,047행) · risk_analysis 2회(64,720행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · users 6,413회

### POST · DELETE /api/me/wishlist (관심 등록 · 해제)

파급 경로(비중 없음). 요청 6,480 · dropped 0 · 요청당 질의 2.99회 · 요청당 DB 실행 0.3 ms · Redis 적중/실패 1/0

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 8 | 14 | 22 | 0.0 % | 0.3 | 0.07 | 0.05 | 0.05 | 0 | 4 |
| 25 | 25.0 | 8 | 14 | 30 | 0.0 % | 0.3 | 0.07 | 0.04 | 0.04 | 0 | 4 |
| 50 | 50.0 | 8 | 11 | 12 | 0.0 % | 0.3 | 0.11 | 0.07 | 0.08 | 0 | 4 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 23.988 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 3229 · 평균 0.158 ms · 행 3229 — `insert into wishlist (alert_condition;created_at;monitoring_yn;property_id;user_id) values ($1;$2;$3;$4;$5) RETURNING *…`
  - 호출 3190 · 평균 0.074 ms · 행 3190 — `delete from wishlist where wish_id=$1…`
- 전체 스캔(회차 중 증가): property_code 134,372회(412,023행) · wishlist_notification 3,182회(0행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · wishlist 9,619회

### POST /api/properties/{id}/risk/reanalyze (재분석)

파급 경로(비중 없음). 요청 6,481 · dropped 0 · 요청당 질의 3.63회 · 요청당 DB 실행 0.3 ms · Redis 적중/실패 6431/1894

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 8 | 56 | 77 | 0.0 % | 0.4 | 0.10 | 0.08 | 0.04 | 0 | 4 |
| 25 | 25.0 | 7 | 49 | 51 | 0.0 % | 0.4 | 0.08 | 0.05 | 0.05 | 0 | 6 |
| 50 | 50.0 | 7 | 12 | 50 | 0.0 % | 0.4 | 0.11 | 0.08 | 0.04 | 0 | 6 |

- 단독 한계: 마지막 단계까지 기준 안
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 23.830 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); $22) AS latitude; ROUND(AVG(g.longitude); $23…`
  - 호출 6430 · 평균 0.060 ms · 행 6430 — `select count(*) from property p1_0 where p1_0.property_id=$1…`
  - 호출 3787 · 평균 0.043 ms · 행 3787 — `select p1_0.property_id;p1_0.address;p1_0.area_sqm;p1_0.built_year;p1_0.contract_type_code_id;p1_0.registered_at;p1_0.de…`
- 전체 스캔(회차 중 증가): guarantee_criteria 947회(2,841행) · guarantee_premium_rate 947회(11,364행) · hf_criteria 947회(947행) · insurance_product 947회(2,841행) · property 2회(67,183행) · property_code 136,269회(423,405행) · risk_analysis 2회(64,720행) · risk_criteria 947회(947행) · sgi_criteria 947회(947행)
- 인덱스 스캔 상위: risk_analysis 71,603회 · property 10,459회

### POST /api/auth/login (로그인)

파급 경로(비중 없음). 요청 6,183 · dropped 298 · 요청당 질의 2.89회 · 요청당 DB 실행 0.3 ms · Redis 적중/실패 0/1

| 단계 | 실제 RPS | p50 ms | p95 ms | p99 ms | 5xx | 응답 KB | CPU APP-01 | CPU APP-02 | CPU DB-01 | Hikari 대기 최대 | Tomcat 사용 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 10.0 | 88 | 98 | 114 | 0.0 % | 0.8 | 0.25 | 0.20 | 0.04 | 0 | 7 |
| 25 | 25.0 | 98 | 142 | 376 | 0.0 % | 0.8 | 0.58 | 0.47 | 0.05 | 0 | 8 |
| 50 | 46.8 | 5437 | 9734 | 10000 | 11.1 % | 1.1 | 0.92 | 0.93 | 0.06 | 164 | 200 |

- 단독 한계: **50 RPS 에서 기준 초과**(p95 9734 ms · 5xx 11.1 %)
- 상위 질의(실행 시간 합 순):
  - 호출 25 · 평균 24.626 ms · 행 145 — `SELECT g.row_index; g.col_index; COUNT(*) AS count; ROUND(AVG(g.latitude); 7) AS latitude; ROUND(AVG(g.longitude); 7) AS…`
  - 호출 5927 · 평균 0.079 ms · 행 5927 — `update user_auth set auth_type=$1;last_login_at=$2;password_hash=$3;provider_id=$4;user_id=$5 where auth_id=$6…`
  - 호출 5927 · 평균 0.065 ms · 행 5927 — `select ua1_0.auth_id;ua1_0.auth_type;ua1_0.created_at;ua1_0.last_login_at;ua1_0.password_hash;ua1_0.provider_id;ua1_0.us…`
- 전체 스캔(회차 중 증가): property 2회(67,183행) · property_code 134,376회(412,047행) · risk_analysis 2회(64,722행)
- 인덱스 스캔 상위: risk_analysis 69,709회 · user_auth 11,808회

## 측정 경과 · 한계

- **첫 묶음 조회 회차(19:40 ~ 19:49, 150 RPS 까지)는 DB 통계를 버렸다** — APP-01 멈춤 · 재부팅과 겹쳐 질의 통계 구간이 어긋났다. k6 수치(50 RPS 에서 p50 5.8 s, DB-01 CPU 0.89)는 다시 잰 회차(`A-clusters2`, 20:08 ~ 20:13, 50 RPS 까지)와 같은 모양이다. 카드는 다시 잰 회차를 쓴다.
- **첫 마커 회차(19:56 ~ 20:05)는 버렸다** — 재부팅 뒤 `app-1` 슬롯이 빠진 상태(슬롯 셋)였다. 카드는 20:18 ~ 20:27 회차.
- **마커 100 RPS 단계는 감시가 끊었다**(20:27:12 — APP-01 SSH 무응답) — 그 단계 수치는 부분이다. 같은 때 앞단 Nginx 컨테이너가 상한 48m 에서 메모리 OOM(작업 프로세스 5회 종료, 20:27:15 ~ 36), 앱 슬롯 둘이 종료 코드 0 으로 다시 떴다(20:27:38 · 44 — 원인 **미확인**, OOM 표시 없음).
- **자치구 수 회차의 첫 단계**는 슬롯이 다시 뜨는 중에 시작했다(20:28) — 10 RPS 단계의 APP-01 CPU 0.49 는 기동 몫이다. 응답 수치는 워밍업 30초 뒤라 영향이 작다.
- **CPU 프로파일(카드 ⑦)은 얻지 못했다** — 9/28 은 APP-01 메모리 고갈과 겹쳤고, 9/29 는 APP-02 슬롯에 붙였으나 **라이브러리가 컨테이너 안에 없어 붙지 않았다**(호스트 `/tmp` 에만 풀었다 — 9/28 도 같은 원인으로 보인다, 추정). 컨테이너 안에 넣는 것은 하지 않고 스레드 덤프로 대신했다(사용자 결정). **스레드 덤프는 넷** — 9/28 묶음 조회 150 RPS(APP-01), 9/29 위험 판정 · 묶음 조회 · 로그인 각 50 RPS(APP-02 `app-1`), 저장소 밖. 앱 쪽 CPU 상위 코드(위험 판정의 앱 시간 등)는 **미측정**.
- **Hikari 대기 · Tomcat 사용 중(카드 ⑩)이 9/28 에 빈 원인은 집계 스크립트였다** — 지표는 Grafana 에 있었다. 운영자 PC(Windows)에서 Git bash 로 PromQL 을 넘길 때 공백 없는 인자(`sum(hikaricp_connections_pending{job="app"})`)의 따옴표가 깨져 `bad_data`(start 누락)가 났고 빈 결과로 처리됐다. 공백이 있는 CPU 질의만 통과했다. 직접 HTTP 로 바꿔 9/28 회차까지 다시 뽑았다.
- 쏠림 · 균등 분포 · 경합 · 깊은 페이지 · B 묶음은 **9/29 에 쟀다**(앞단 상한 안, 50 RPS 까지). 캐시 빈 상태는 따로 재지 않았다 — 캐시는 자치구 수 하나이고 T5(용량 산정 리포트)가 이미 쟀다.
- **9/29 첫 회차 C-profile2 · C-subs 의 50 단계는 무효** — 반복 하나가 GET · PUT 두 요청이라 초당 100건이 앞단 상한 60 에 걸렸다(429). 10 · 20 · 28 로 다시 잰 C-profile3 · C-subs2 를 카드로 쓴다.
- **9/29 부하 중 APP-01 을 거치는 SSH 가 끊겼다**(B-registry · B-reanalyze 회차, 09:28 ~ 09:32 감시 무응답) — k6 는 LOAD-01 안에서 끝까지 돌았고 요약도 남았다. B-reanalyze 는 수집 끝이 k6 끝보다 15분 늦어 질의 통계 구간이 길다(그 사이 부하 없음). APP-01 가용 메모리는 9/29 부하 중 100 ~ 250 MB(최저 약 99 MB, 로그인 회차) — 핵심 발견 3 과 같다.
- **로그인 회차는 dropped 298** — 포화로 VU 가 모자랐다(한계 판정에는 영향 없음).
- 요청당 질의 수에는 k6 setup(자치구 25개 묶음 조회 등 약 50건)이 섞인다 — 요청 수에 비해 작다.
- **기존 시험 계정(001 · 1001 · 5101 구간)이 9/29 에 저장된 비밀번호로 로그인되지 않았다** — 원인 미확인(계정 행을 읽는 조회는 하지 않았다). 새로 가입한 계정은 같은 비밀번호로 됐다.

## 되돌린 것

| 날짜 · 시각 | 한 것 | 되돌림 · 확인 |
|---|---|---|
| 9/28 19:07:13 | 앞단 전체 상한 60 → 300 r/s | 21:46:23 60 으로 되돌림 — `nginx -T` 확인 |
| 9/28 | LOAD-01 생성 · 노드 넷 기동 | 21:46 LOAD-01 terminate · 노드 넷 stop |
| 9/29 08:33 | 노드 넷 기동 · LOAD-01 새로 생성(`i-021451ed347f5e4c9`) | 11:25:45 LOAD-01 terminate · 노드 넷 stop — 태그 조회로 확인, EIP 0 |
| 9/29 08:35:56 | 네 노드 `dnf-automatic.timer` 정지(놓친 실행은 먼저 돌림 — 설치 없음) | 11:25 다시 시작 — 네 노드 `active` 확인 |
| 9/29 | 앞단 상한 — **바꾸지 않았다** | 11:25:20 `nginx -T` 60 r/s 확인 |
| 9/29 | APP-02 호스트 `/tmp` 에 async-profiler · 결과 파일 | 11:25 삭제 확인 |
| 9/29 11:24:51 | **시험 계정 삭제(사용자 지시)** — `loadtest+%@rental.test` · 역할 USER **2,020개**와 딸린 행(관심 16,323 · 구독 140, 알림 · 상담 0). 한 트랜잭션, 먼저 ROLLBACK 으로 건수 확인 | 남은 시험 계정 0 확인. 되돌리지 않는다(시험 데이터) — 다음 부하 시험은 `make-tokens.js` 가 다시 가입시킨다 |
