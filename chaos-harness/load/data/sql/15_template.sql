-- 판정 원본 풀 (#376). 반영 전부터 운영에 있던, 앱이 판정한 매물만 원본이 된다(최신 판정 + 등기가 있는 것).
-- 고르는 순서: ① 같은 건물 · 같은 면적대 ② 같은 건물 ③ 같은 구 · 유형 · 면적대 · 보증금/시세 10% 구간 ④ 같은 유형 · 면적대
-- 한 묶음 안에서는 새 매물 식별자로 골고루 고른다(rn = id % n + 1).
\set ON_ERROR_STOP 1
CREATE OR REPLACE FUNCTION loadtest.area_band(a NUMERIC) RETURNS TEXT IMMUTABLE LANGUAGE sql AS $$
    SELECT CASE WHEN a < 40 THEN 'UNDER_40' WHEN a < 60 THEN 'FROM_40_TO_60' WHEN a < 85 THEN 'FROM_60_TO_85'
                WHEN a < 135 THEN 'FROM_85_TO_135' ELSE 'OVER_135' END $$;
CREATE OR REPLACE FUNCTION loadtest.ratio_band(deposit BIGINT, market BIGINT) RETURNS INT IMMUTABLE LANGUAGE sql AS $$
    SELECT least((deposit * 10 / nullif(market, 0))::INT, 15) $$;

DROP TABLE IF EXISTS loadtest.tmpl;
CREATE TABLE loadtest.tmpl AS
SELECT p.property_id AS template_id,
       concat_ws('|', p.sigungu_code, p.bjdong_code, p.bun, p.ji, loadtest.area_band(p.area_sqm)) AS g1,
       concat_ws('|', p.sigungu_code, p.bjdong_code, p.bun, p.ji)                               AS g2,
       concat_ws('|', p.district, p.property_type_code_id, loadtest.area_band(p.area_sqm),
                 loadtest.ratio_band(p.deposit, p.market_price))                                AS g3,
       concat_ws('|', p.property_type_code_id, loadtest.area_band(p.area_sqm))                  AS g4,
       p.sigungu_code IS NOT NULL                                                               AS has_key
  FROM property p
  JOIN risk_analysis ra ON ra.property_id = p.property_id AND ra.is_latest
  JOIN building_registry br ON br.registry_id = ra.registry_id
 WHERE p.property_id <= (SELECT max_property_id FROM loadtest.baseline);

DO $$
DECLARE g TEXT;
BEGIN
    FOREACH g IN ARRAY ARRAY['g1', 'g2', 'g3', 'g4'] LOOP
        EXECUTE format('DROP TABLE IF EXISTS loadtest.tmpl_%1$s', g);
        EXECUTE format($q$CREATE TABLE loadtest.tmpl_%1$s AS
                SELECT %1$s AS gkey, template_id,
                       row_number() OVER (PARTITION BY %1$s ORDER BY template_id) AS rn,
                       count(*)     OVER (PARTITION BY %1$s)                      AS n
                  FROM loadtest.tmpl WHERE %2$s$q$, g, CASE WHEN g IN ('g1', 'g2') THEN 'has_key' ELSE 'true' END);
        EXECUTE format('CREATE INDEX ON loadtest.tmpl_%1$s (gkey, rn)', g);
    END LOOP;
END $$;

-- 같은 건물(조회 키)의 건축HUB 실대장 — 대장은 건물 단위(표제부)라 같은 건물의 새 매물이 그대로 쓴다
DROP TABLE IF EXISTS loadtest.hub_ledger;
CREATE TABLE loadtest.hub_ledger AS
SELECT DISTINCT ON (g2) concat_ws('|', p.sigungu_code, p.bjdong_code, p.bun, p.ji) AS g2, l.ledger_id
  FROM building_ledger l JOIN property p ON p.property_id = l.property_id
 WHERE l.data_source = 'BUILDING_HUB' AND p.sigungu_code IS NOT NULL
 ORDER BY g2, l.ledger_id;
CREATE UNIQUE INDEX ON loadtest.hub_ledger (g2);

SELECT (SELECT count(*) FROM loadtest.tmpl) AS templates,
       (SELECT count(*) FROM loadtest.hub_ledger) AS buildings_with_hub_ledger,
       (SELECT count(DISTINCT gkey) FROM loadtest.tmpl_g2) AS buildings_with_template;
