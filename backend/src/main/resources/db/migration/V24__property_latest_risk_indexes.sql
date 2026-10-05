-- 매물 최신 판정 비정규화(PROP-02 · #403) — 3/4 인덱스 둘. CREATE INDEX CONCURRENTLY, 트랜잭션 밖.
--
-- 1) ix_property_lease_ratio — V20 의 ix_risk_analysis_latest_lease_ratio 가 하던 역할(자치구 · 매물 조건 필터가 없는 전세가율순
--    목록을 인덱스 순서로 읽고 LIMIT 에서 멈춤)을 매물 쪽으로 옮긴다. 정의는 V20 과 같은 모양 — (전세가율, 매물 ID) 순, 등급은
--    INCLUDE, 부분 조건은 전세가율 있음. 질의 쪽 범위 조건(미분석 대체값 경계)이 필요한 것도 V20 과 같다(매퍼 주석).
-- 2) idx_property_district_lat_lng_cov — V15 의 idx_property_district_lat_lng 을 대신할 커버링. V19 가 판정 표 인덱스에 INCLUDE 로
--    담아 지도 2단계의 판정 조인을 인덱스 전용 스캔으로 만든 역할을 매물 쪽으로 옮긴다. 지도 묶음이 읽는 열(매물 ID · 등급)을 담아
--    필터 없는 지도 묶음 · 반경 후보가 매물 힙(E01 강남 7,593 버퍼)도 읽지 않게 한다. 자치구 집계(자치구 · 등급)도 이 인덱스로
--    인덱스 전용 스캔을 할 수 있어 집계용 인덱스를 따로 두지 않는다 — 실행 계획은 운영 EXPLAIN 으로 확인한다(미검증). 옛 것을 지우고
--    이름을 옮기는 것은 V25. 대가 — 등급이 인덱스 열이 되어 등급이 바뀌는 매물 UPDATE 가 HOT 이 되지 않는다. 등급 변경은 드물고,
--    매물 갱신은 이미 재분석 대기 부분 인덱스(V18) 때문에 HOT 이 아니다(V19 주석).
--
-- 트랜잭션 밖에서 도는 근거 — flyway-database-postgresql 12.4.0 PostgreSQLParser 가 ^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY 로
-- 시작하는 문장을 「트랜잭션 안에서 실행 불가」로 판정하고(detectCanExecuteInTransaction), flyway-core 12.4.0 ParserSqlScript 가 그런
-- 문장이 있는 파일을 트랜잭션 없이 실행한다(executeInTransaction). 한 파일에 트랜잭션 문장이 섞이면 mixed(기본 false)라 Flyway 가
-- 거부한다 — 그래서 이 파일에는 CONCURRENTLY 두 문장만 둔다. SET LOCAL lock_timeout 도 넣지 않는다(트랜잭션 문장이고, 트랜잭션 밖에서는
-- 뜻이 없다).
--
-- 잠금 — SHARE UPDATE EXCLUSIVE. 매물 읽기 · 쓰기를 막지 않는다(일반 CREATE INDEX 는 SHARE 라 쓰기를 막는다). 대신 표를 두 번 훑고,
-- 시작 시점에 돌던 트랜잭션들이 끝나기를 기다린다 — 오래 열린 트랜잭션이 있으면 그만큼 기다린다(앱 커넥션은
-- idle_in_transaction_session_timeout 60s · statement_timeout 30s, #405). Flyway 커넥션은 statement_timeout 0 이라 끊기지 않는다.
--
-- 중간에 실패하면 — 트랜잭션 밖이라 되돌려지지 않는다. Flyway 는 이 버전을 실패(success = false)로 이력에 남기고
-- (flyway-core 12.4.0 DbMigrate — DDL 트랜잭션이 아닌 실패), 다음 기동은 「failed migration」으로 멈춘다. 실패한 CONCURRENTLY 는
-- INVALID 인덱스를 남긴다. 복구 — DROP INDEX IF EXISTS ix_property_lease_ratio, idx_property_district_lat_lng_cov 로 남은 것을 지우고,
-- flyway_schema_history 의 V24 실패 행을 지운 뒤(flyway repair 와 같다) 다시 기동한다. IF NOT EXISTS 를 쓰지 않는 이유 — INVALID
-- 인덱스가 남아 있으면 조용히 건너뛰지 않고 실패해 드러나게 한다.

CREATE INDEX CONCURRENTLY ix_property_lease_ratio ON property (lease_ratio, property_id)
    INCLUDE (risk_grade) WHERE lease_ratio IS NOT NULL;

CREATE INDEX CONCURRENTLY idx_property_district_lat_lng_cov ON property (district, latitude, longitude)
    INCLUDE (property_id, risk_grade);
