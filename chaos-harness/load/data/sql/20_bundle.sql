-- 자치구 하나의 판정 묶음 (#376) — 등기 · 갑구 · 을구 · 대장 · 최신 판정.
-- 등기 · 갑구 · 을구는 판정 원본을 복사한다. 현재 소유자만 새 매물 규칙(임대인명, 의도적 불일치 20%)으로 바꾼다.
-- 대장: 같은 건물의 건축HUB 실대장이 있으면 그것, 없으면 원본 대장을 MOCK 으로(주소는 새 매물), 원본도 없으면 매물에서 지은
--       MOCK 대장(25_ledger_fill.sql 과 같은 규칙). 새 매물은 모두 대장을 갖는다 — 대장이 없으면 위험도 · 대출 · 대장 조회가
--       건축HUB 를 부른다(LedgerCommandService.collectIfAbsent 는 행이 있는지만 본다). 운영 대장 모드 real 에서 MOCK 은 판정 입력이 아니다.
-- 판정 행의 결론 · 근거(V21)는 뒤의 54_judgement.sql 이 앱 산식으로 다시 쓴다 — 여기서 넣는 값은 자리를 잡는 초벌이다.
-- 판정: 보증 3사 결과는 원본, 전세가율 · 깡통전세 · 등급은 앱 산식(NegativeEquityCalculator · RiskGradeCalculator)으로
--       새 매물의 보증금 · 시세와 복사한 선순위채권으로 다시 계산한다.
-- 실행: psql -v ON_ERROR_STOP=1 -v district=종로구 -f 20_bundle.sql
\set ON_ERROR_STOP 1
BEGIN;

-- 1. 원본 고르기
CREATE TEMP TABLE bundle_map ON COMMIT DROP AS
WITH todo AS (
    SELECT o.property_id, o.registry_owner, p.address, p.area_sqm, p.deposit, p.market_price,
           concat_ws('|', p.sigungu_code, p.bjdong_code, p.bun, p.ji, loadtest.area_band(p.area_sqm)) AS k1,
           concat_ws('|', p.sigungu_code, p.bjdong_code, p.bun, p.ji)                               AS k2,
           concat_ws('|', p.district, p.property_type_code_id, loadtest.area_band(p.area_sqm),
                     loadtest.ratio_band(p.deposit, p.market_price))                                AS k3,
           concat_ws('|', p.property_type_code_id, loadtest.area_band(p.area_sqm))                  AS k4,
           p.sigungu_code IS NOT NULL AS has_key
      FROM loadtest.property_origin o
      JOIN property p ON p.property_id = o.property_id
     WHERE o.district = :'district' AND NOT o.bundled
)
SELECT t.*,
       coalesce(
           (SELECT x.template_id FROM loadtest.tmpl_g1 x
             WHERE t.has_key AND x.gkey = t.k1 AND x.rn = t.property_id % x.n + 1),
           (SELECT x.template_id FROM loadtest.tmpl_g2 x
             WHERE t.has_key AND x.gkey = t.k2 AND x.rn = t.property_id % x.n + 1),
           (SELECT x.template_id FROM loadtest.tmpl_g3 x WHERE x.gkey = t.k3 AND x.rn = t.property_id % x.n + 1),
           (SELECT x.template_id FROM loadtest.tmpl_g4 x WHERE x.gkey = t.k4 AND x.rn = t.property_id % x.n + 1)
       ) AS template_id,
       nextval(pg_get_serial_sequence('building_registry', 'registry_id')) AS new_registry_id
  FROM todo t;

DELETE FROM bundle_map WHERE template_id IS NULL;   -- 원본이 하나도 없는 유형 · 면적대 — 판정 없이 둔다(미분석)

ALTER TABLE bundle_map ADD COLUMN template_registry_id BIGINT, ADD COLUMN template_ledger_id BIGINT,
                       ADD COLUMN hub_ledger_id BIGINT, ADD COLUMN new_ledger_id BIGINT;
UPDATE bundle_map m SET template_registry_id = ra.registry_id, template_ledger_id = ra.ledger_id
  FROM risk_analysis ra WHERE ra.property_id = m.template_id AND ra.is_latest;
UPDATE bundle_map m SET hub_ledger_id = h.ledger_id
  FROM loadtest.hub_ledger h WHERE m.has_key AND h.g2 = m.k2;
UPDATE bundle_map SET new_ledger_id = nextval(pg_get_serial_sequence('building_ledger', 'ledger_id'));

-- 2. 등기 표제부
INSERT INTO building_registry (registry_id, property_id, building_purpose, building_structure, data_source,
                               registry_address, exclusive_area, created_at, updated_at)
SELECT m.new_registry_id, m.property_id, r.building_purpose, r.building_structure, r.data_source, m.address,
       m.area_sqm, now(), now()
  FROM bundle_map m JOIN building_registry r ON r.registry_id = m.template_registry_id;

-- 3. 갑구 — 현재 소유자는 새 매물 규칙의 이름
INSERT INTO ownership_history (registry_id, owner_name, ownership_date, provisional_seizure_yn, seizure_yn,
                               auction_yn, provisional_registration_yn, trust_registration_yn, lease_registration_yn,
                               is_current, recorded_at, rank_no, right_type, registration_cause)
SELECT m.new_registry_id, CASE WHEN o.is_current THEN m.registry_owner ELSE o.owner_name END, o.ownership_date,
       o.provisional_seizure_yn, o.seizure_yn, o.auction_yn, o.provisional_registration_yn, o.trust_registration_yn,
       o.lease_registration_yn, o.is_current, now(), o.rank_no, o.right_type, o.registration_cause
  FROM bundle_map m JOIN ownership_history o ON o.registry_id = m.template_registry_id;

-- 4. 을구
INSERT INTO mortgage_history (registry_id, priority_no, right_type, receipt_date, registration_cause, mortgage_amount,
                              mortgage_creditor, debtor_name, max_bond_amount, prior_tenant_deposit, lease_right_yn,
                              tenancy_right_yn, senior_debt_yn, is_active, recorded_at)
SELECT m.new_registry_id, h.priority_no, h.right_type, h.receipt_date, h.registration_cause, h.mortgage_amount,
       h.mortgage_creditor, h.debtor_name, h.max_bond_amount, h.prior_tenant_deposit, h.lease_right_yn,
       h.tenancy_right_yn, h.senior_debt_yn, h.is_active, now()
  FROM bundle_map m JOIN mortgage_history h ON h.registry_id = m.template_registry_id;

-- 5. 대장 — 실대장 · 원본 대장이 없으면 매물에서 짓는다(MOCK). 지은 값의 규칙은 25_ledger_fill.sql 머리 주석
INSERT INTO building_ledger (ledger_id, property_id, ledger_address, owner_name, building_purpose, building_structure,
                             building_area, violation_yn, total_floor_area, exclusive_area, approval_date, data_source,
                             created_at, updated_at)
SELECT m.new_ledger_id, m.property_id,
       CASE WHEN m.hub_ledger_id IS NOT NULL THEN l.ledger_address ELSE m.address END,
       CASE WHEN l.ledger_id IS NOT NULL THEN l.owner_name ELSE p.landlord_name END,
       coalesce(l.building_purpose, CASE pc.code_value WHEN 'APARTMENT' THEN '공동주택' ELSE '업무시설' END),
       CASE WHEN l.ledger_id IS NOT NULL THEN l.building_structure ELSE '철근콘크리트구조' END,
       CASE WHEN l.ledger_id IS NOT NULL THEN l.building_area
            ELSE least(round(m.area_sqm * (30 + (hashint8(m.property_id * 3 + 1) & 2147483647) % 120)
                       * (10 + (hashint8(m.property_id * 3 + 2) & 2147483647) % 30) / 100, 2), 99999.99) END,
       CASE WHEN l.ledger_id IS NOT NULL THEN l.violation_yn
            ELSE (hashint8(m.property_id * 5 + 1) & 2147483647) % 100 < 5 END,
       CASE WHEN l.ledger_id IS NOT NULL THEN l.total_floor_area
            ELSE round(m.area_sqm * (30 + (hashint8(m.property_id * 3 + 1) & 2147483647) % 120), 2) END,
       CASE WHEN m.hub_ledger_id IS NOT NULL THEN l.exclusive_area ELSE m.area_sqm END,
       CASE WHEN l.ledger_id IS NOT NULL THEN l.approval_date
            ELSE DATE '1985-01-01' + ((hashint8(m.property_id * 5 + 2) & 2147483647) % 7300)::int END,
       CASE WHEN m.hub_ledger_id IS NOT NULL THEN l.data_source ELSE 'MOCK' END, now(), now()
  FROM bundle_map m
  JOIN property p ON p.property_id = m.property_id
  JOIN property_code pc ON pc.code_id = p.property_type_code_id
  LEFT JOIN building_ledger l ON l.ledger_id = coalesce(m.hub_ledger_id, m.template_ledger_id)
 ORDER BY m.property_id;

-- 6. 최신 판정 — 선순위채권 = 활성 · 선순위 을구의 채권최고액 + 선순위 임차보증금(NegativeEquityCalculator)
WITH senior AS (
    SELECT m.property_id, coalesce(sum(h.max_bond_amount + coalesce(h.prior_tenant_deposit, 0))
                                   FILTER (WHERE h.senior_debt_yn AND h.is_active), 0) AS senior_debt
      FROM bundle_map m LEFT JOIN mortgage_history h ON h.registry_id = m.new_registry_id
     GROUP BY m.property_id
), crit AS (
    SELECT negative_equity_ratio, caution_lease_ratio FROM risk_criteria ORDER BY risk_criteria_id ASC LIMIT 1   -- 앱 findFirstByOrderByRiskCriteriaIdAsc 와 같은 쪽
), calc AS (
    SELECT m.*, ra.eligible_guarantee_id, ra.hug_eligible_yn, ra.hf_eligible_yn, ra.sgi_eligible_yn,
           ra.insurance_eligible_yn, ra.analyzed_at, s.senior_debt + m.deposit AS risk_amount, c.*
      FROM bundle_map m
      JOIN senior s ON s.property_id = m.property_id
      JOIN risk_analysis ra ON ra.property_id = m.template_id AND ra.is_latest
      CROSS JOIN crit c
)
INSERT INTO risk_analysis (property_id, registry_id, ledger_id, eligible_guarantee_id, lease_ratio, hug_eligible_yn,
                           hf_eligible_yn, sgi_eligible_yn, insurance_eligible_yn, risk_grade, previous_grade,
                           risk_reason, is_latest, analyzed_at)
SELECT property_id, new_registry_id, new_ledger_id, eligible_guarantee_id,
       least(round(risk_amount * 100.0 / market_price, 2), 999.99), hug_eligible_yn, hf_eligible_yn, sgi_eligible_yn,
       insurance_eligible_yn,
       CASE WHEN risk_amount * 100 > market_price * negative_equity_ratio THEN 'DANGER'
            WHEN NOT insurance_eligible_yn THEN 'DANGER'
            WHEN risk_amount * 100 > market_price * caution_lease_ratio THEN 'CAUTION'
            ELSE 'SAFE' END,
       NULL,
       CASE WHEN risk_amount * 100 > market_price * negative_equity_ratio THEN 'NEGATIVE_EQUITY'
            WHEN NOT insurance_eligible_yn THEN 'INSURANCE_INELIGIBLE'
            WHEN risk_amount * 100 > market_price * caution_lease_ratio THEN 'LEASE_RATIO_CAUTION'
            ELSE 'INSURANCE_ELIGIBLE' END,
       TRUE, analyzed_at
  FROM calc;

UPDATE loadtest.property_origin o SET bundled = TRUE, template_id = m.template_id
  FROM bundle_map m WHERE o.property_id = m.property_id;

SELECT ra.risk_grade, count(*)
  FROM loadtest.property_origin o JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
 WHERE o.district = :'district' GROUP BY 1 ORDER BY 1;
COMMIT;
