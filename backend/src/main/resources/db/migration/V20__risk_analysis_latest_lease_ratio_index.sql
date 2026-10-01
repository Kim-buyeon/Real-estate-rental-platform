-- 자치구 없는 전세가율순 목록(PROP-01 · #342 · #347). 질의 쪽 변경(매물 매퍼 selectListByLeaseRatioIndex)과 짝이다.
--
-- 근거 — 운영 DB-01(2026-10-01, 이 인덱스 12MB 를 CONCURRENTLY 로 먼저 만든 뒤 지금 쿼리와 인덱스 출발 쿼리를 번갈아 8회,
-- 첫 회 제외 중앙값). 원자료는 #347 측정 결과 코멘트. 결과 행은 모든 경우 차이 0.
--   필터 없음 오름 693.5 → 0.3ms, 필터 없음 내림 816.1 → 0.3ms, 등급(SAFE) 오름 370.1 → 0.3ms,
--   커서(80.00, 150000) 오름 517.6 → 0.4ms.
--   매물 조건 필터(오피스텔 · 반전세 · 보증금 1 ~ 2억)가 있으면 내림 88.3 → 1.2ms 지만 오름 93.9 → 313.3ms 로 나빠져,
--   매퍼는 매물 조건 필터가 없을 때만 이 인덱스에서 출발한다. 등급 필터는 판정 표 안의 열(INCLUDE)이라 허용한다.
--
-- 판정 표에서 최신 판정만, (lease_ratio, property_id) 순으로 담는다. 목록의 정렬(전세가율, 매물 ID)과 키셋 커서를 이 순서로
-- 읽고 LIMIT 에서 멈춘다. risk_grade 는 등급 필터를 표 방문 없이 거르려고 INCLUDE 로 둔다.
-- lease_ratio 는 NOT NULL(V1)이라 IS NOT NULL 조건은 지금 행을 줄이지 않는다. 운영에 만든 정의와 글자까지 같게 두었다 —
-- 정의가 다르면 같은 이름의 다른 인덱스가 환경마다 남는다.
--
-- 질의가 이 부분 인덱스를 정렬 경로로 쓰려면 lease_ratio 범위 조건(오름 < 1000 · 내림 > -1000, 미분석 대체값의 경계)이 있어야
-- 한다 — lease_ratio IS NOT NULL 만 있으면 플래너가 쓰지 않았다(정렬을 금지해도, #347 측정). 매퍼 주석에도 같은 내용이 있다.
--
-- 운영에는 #347 측정 때 CONCURRENTLY 로 이미 만들어 두었다 — IF NOT EXISTS 로 건너뛴다(NOTICE 만 남는다).
-- CONCURRENTLY 를 쓰지 않는다 — V19 와 같다. Flyway 는 PostgreSQL 마이그레이션을 한 트랜잭션으로 감싸고, CONCURRENTLY 는
-- 트랜잭션 안에서 돌 수 없다. 새로 만드는 곳은 로컬 · CI(테스트 컨테이너)뿐이라 표가 작고, 운영은 이름이 있어 건너뛴다.
-- lock_timeout 5s 는 V19 와 같은 이유다 — 운영 기본값은 0(무한 대기)이다. 건너뛰는 경우에도 이름 확인 전에 표 잠금을 먼저
-- 잡으므로 잠금 대기가 길면 기다리지 않고 실패시켜 배포를 멈춘다. SET LOCAL 은 트랜잭션 끝에서 풀린다.

SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS ix_risk_analysis_latest_lease_ratio ON risk_analysis (lease_ratio, property_id)
    INCLUDE (risk_grade) WHERE is_latest AND lease_ratio IS NOT NULL;
