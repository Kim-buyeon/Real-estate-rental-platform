-- 관심 매물 고르기 표 (#376) — 판정된 매물을 상위 3개 구(HOT)와 나머지(REST)로 나눠 번호를 매긴다.
-- 41_wishlist.sql 이 구간마다 다시 만들지 않도록 한 번만 만든다. 끝나면 지운다.
\set ON_ERROR_STOP 1
DROP TABLE IF EXISTS loadtest.pick, loadtest.zone_size;
CREATE TABLE loadtest.pick AS
WITH ranked AS (SELECT district, row_number() OVER (ORDER BY count(*) DESC) AS rk FROM property GROUP BY district)
SELECT p.property_id, CASE WHEN r.rk <= 3 THEN 'HOT' ELSE 'REST' END AS zone,
       row_number() OVER (PARTITION BY CASE WHEN r.rk <= 3 THEN 'HOT' ELSE 'REST' END ORDER BY p.property_id) AS rn
  FROM property p JOIN ranked r ON r.district = p.district
 WHERE p.risk_grade IS NOT NULL;   -- 최신 판정이 있는 매물(V22 비정규화 열 — 판정 표를 조인하지 않는다)
CREATE INDEX ON loadtest.pick (zone, rn);
CREATE TABLE loadtest.zone_size AS SELECT zone, max(rn) AS n FROM loadtest.pick GROUP BY zone;
ANALYZE loadtest.pick;
SELECT * FROM loadtest.zone_size;
