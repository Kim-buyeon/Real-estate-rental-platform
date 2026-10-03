-- 자치구 하나의 매물 반영 (#376). 실 → 가짜(무작위 순번 fake_limit 이하) 순서로 넣는다.
-- 앱 자연키 (address, area_sqm, floor, deposit, monthly_rent) 가 이미 있으면 넣지 않는다 — 기존 31만과 같은 실거래.
-- 실행: psql -v ON_ERROR_STOP=1 -v district=종로구 -v fake_limit=2480000 -f 10_property.sql
\set ON_ERROR_STOP 1
BEGIN;

-- 이 구에서 넣을 행에 식별자를 미리 받는다(identity 시퀀스를 그대로 쓴다 — 이후 앱의 저장과 겹치지 않는다)
WITH todo AS (
    SELECT s.ctid AS row_ref
      FROM loadtest.stage_property s
     WHERE s.district = :'district'
       AND s.property_id IS NULL
       AND (s.kind = 'REAL' OR s.seq <= :fake_limit)
       -- 겹칠 수 있는 것은 반영 전 매물뿐이다 — 생성기가 실 · 가짜를 합쳐 자연키가 겹치지 않게 만들었다(gen_properties seen).
       -- 범위를 좁히지 않으면 구마다 매물 전체(수백만)를 훑는다(#376 서초구 29분)
       AND NOT EXISTS (SELECT 1 FROM property p
                        WHERE p.property_id <= (SELECT max_property_id FROM loadtest.baseline)
                          AND p.address = s.address AND p.area_sqm = s.area_sqm
                          AND p.floor IS NOT DISTINCT FROM s.floor
                          AND p.deposit = s.deposit AND p.monthly_rent = s.monthly_rent)
     ORDER BY s.kind DESC, s.seq        -- REAL 먼저
)
UPDATE loadtest.stage_property s
   SET property_id = nextval(pg_get_serial_sequence('property', 'property_id'))
  FROM todo
 WHERE s.ctid = todo.row_ref;

INSERT INTO property (property_id, address, district, landlord_name, contract_type_code_id, property_type_code_id,
                      status_code_id, deposit, monthly_rent, market_price, price_type, price_date, area_sqm, floor,
                      built_year, latitude, longitude, sigungu_code, bjdong_code, bun, ji)
SELECT s.property_id, s.address, s.district, s.landlord_name, ct.code_id, pt.code_id, st.code_id, s.deposit,
       s.monthly_rent, s.market_price, s.price_type, s.price_date, s.area_sqm, s.floor, s.built_year, s.latitude,
       s.longitude, nullif(s.sigungu_code, ''), nullif(s.bjdong_code, ''), nullif(s.bun, ''), nullif(s.ji, '')
  FROM loadtest.stage_property s
  JOIN property_code ct ON ct.code_group = 'CONTRACT_TYPE' AND ct.code_value = s.contract_type
  JOIN property_code pt ON pt.code_group = 'PROPERTY_TYPE' AND pt.code_value = s.property_type
  JOIN property_code st ON st.code_group = 'PROPERTY_STATUS' AND st.code_value = 'AVAILABLE'
 WHERE s.district = :'district'
   AND s.property_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM loadtest.property_origin o WHERE o.property_id = s.property_id)
 ORDER BY s.property_id;

INSERT INTO loadtest.property_origin (property_id, kind, district, registry_owner)
SELECT s.property_id, s.kind, s.district, s.registry_owner
  FROM loadtest.stage_property s
 WHERE s.district = :'district'
   AND s.property_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM loadtest.property_origin o WHERE o.property_id = s.property_id);

SELECT kind, count(*) FROM loadtest.property_origin WHERE district = :'district' GROUP BY kind ORDER BY kind;
COMMIT;
