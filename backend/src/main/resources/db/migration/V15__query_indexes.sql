-- 부하 시험(INF-06 · #270)에서 DB CPU 를 가장 많이 쓴 조회의 인덱스. 질의는 바꾸지 않는다.
--
-- 근거 — pg_stat_statements(T2, 50 RPS 까지 누적)의 실행 시간 상위와 EXPLAIN ANALYZE:
-- 1) property (district, latitude, longitude) — 지도 묶음 조회(명세 매물 1.12)와 마커 조회는 자치구 + 좌표 범위로 거른다.
--    V3 의 (latitude, longitude) 는 위도 범위를 훑은 뒤 자치구를 행마다 거른다(강서구 — 비트맵 12.7ms, 498행 버림).
-- 2) property (district, registered_at DESC, property_id DESC) — 목록(명세 매물 1.6)의 기본 정렬(등록순 · 식별자로 마무리).
--    자치구 인덱스가 없어 67,183행 전체를 훑고 정렬했다(23ms). 이 순서로 읽으면 LIMIT 에서 멈춘다.
--    다른 정렬(보증금 · 전세가율)은 1) 로 자치구만 좁힌다.
-- 3) ownership_history (registry_id) · mortgage_history (registry_id) — 위험도 분석의 등기 이력 조회와 마커의 선순위 채무
--    EXISTS 가 registry_id 로 찾는다. V1 의 FK 는 인덱스를 만들지 않아 조회마다 전체 스캔이었다(idx_scan 0).
--    지금 행 수는 작지만 정의 규모(매물당 8건, 트래픽 정의서 6장)에서는 비례해 커진다.
--
-- CONCURRENTLY 를 쓰지 않는다 — Flyway 가 마이그레이션을 트랜잭션으로 감싸고, 이 크기(property 67,183행)에서 쓰기 잠금은
-- 짧다(운영 DB 에서 트랜잭션 안에 만들고 되돌려 잰 시간 — PR 본문).

CREATE INDEX idx_property_district_lat_lng ON property (district, latitude, longitude);

CREATE INDEX idx_property_district_registered ON property (district, registered_at DESC, property_id DESC);

CREATE INDEX idx_ownership_history_registry ON ownership_history (registry_id);

CREATE INDEX idx_mortgage_history_registry ON mortgage_history (registry_id);
