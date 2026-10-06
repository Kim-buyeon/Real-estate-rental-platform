-- 보증 3사 · 위험 등급 판정의 SQL 재현 — 공용 계산부 (#376). 51_guarantee_check.sql · 52_guarantee_recalc.sql · 54_judgement.sql 이 앞에 붙여 쓴다.
-- 혼자 실행하지 않는다. 세션 임시 함수 둘과 임시 뷰 gc_calc 를 만든다(실제 표는 읽기만 한다).
--
-- 변수 (psql -v)
--   new_rows            true = 이번에 넣은 매물(loadtest.property_origin), false = 기준선 이하 매물(앱이 판정한 것). 기본 false
--   lo · hi             property_id 범위(양끝 포함). 기본 전체
--   ignore_mock_ledger  true = data_source = 'MOCK' 대장을 「대장 없음」으로 본다. 기본 true — 아래 「가정」
--
-- 앱과 맞춘 것 (RiskAnalysisCommandService.judgeAndRecord · record, calculator/*)
--   입력       매물(보증금 · 시세 · 임대인명 · 유형 코드), 등기 표제부는 property_id 로(findByPropertyId — 판정 행의 registry_id 가 아니다),
--              갑구 · 을구는 그 등기의 registry_id 로, 대장은 property_id 로. 기준은 risk_criteria 의 risk_criteria_id 최소 행,
--              guarantee_criteria 기관별 1행(provider UNIQUE), SGI 아파트 무제한은 sgi_criteria(그 guarantee_id 행이 없으면 거짓)
--   선순위채권 senior_debt_yn AND is_active 인 을구의 max_bond_amount + coalesce(prior_tenant_deposit, 0) 합, 없으면 0. numeric 으로 더한다
--   비교       반올림 없는 교차곱 — 깡통전세 (선순위 + 보증금) × 100 > 시세 × negative_equity_ratio,
--              기관 전세가율 (선순위 + 보증금) × 100 > 시세 × collateral_ratio, 선순위 비율 선순위 × 100 > 시세 × 한도(한도 NULL 이면 검사 안 함),
--              CAUTION (선순위 + 보증금) × 100 > 시세 × caution_lease_ratio. 모두 「초과」만 걸린다
--   보증금 한도 apartment_unlimited AND 유형 APARTMENT 이면 검사 안 함, 아니면 보증금 > max_deposit
--   위반건축물 대장 표기가 TRUE 일 때만(NULL 은 사유 아님), 기관의 violation_disqualify_yn 이 참일 때
--   권리 침해 is_current 이고 right_type 이 SEIZURE · PROVISIONAL_SEIZURE · AUCTION_COMMENCEMENT · TRUST 인 갑구가 하나라도 있고
--              기관의 right_violation_disqualify_yn 이 참일 때(OwnershipRightType 의 VIOLATION 분류)
--   명의       현재 소유자 = is_current 이고 OWNERSHIP_PRESERVATION · OWNERSHIP_TRANSFER 인 행 중 rank_no 최대. 같은 rank_no 면
--              ownership_id 가 작은 쪽(Stream.max 는 동률이면 먼저 온 것 — 앱 조회는 ORDER BY 가 없어 색인 · 넣은 순서다).
--              이름은 Java String.strip() 과 같은 문자 집합(gc_jstrip)으로 앞뒤만 걷고 완전 일치. 그런 행이 없으면 불일치
--   주소       대장을 쓸 때만 본다. 앞뒤를 strip 한 뒤 Java 정규식 \s+ (= [ \t\n\x0B\f\r]+) 를 한 칸으로 접고 완전 일치.
--              어느 한쪽이 NULL 이면 불일치(FALSE). 대장을 쓰지 않으면 NULL — 사유가 아니다
--   등급 · 사유 깡통전세 → DANGER · NEGATIVE_EQUITY, 3사 불가 → DANGER · INSURANCE_INELIGIBLE, CAUTION 초과 → CAUTION ·
--              LEASE_RATIO_CAUTION, 그 외 SAFE · INSURANCE_ELIGIBLE (GradeReason 선언 순)
--   가입 기관  가입 가능한 첫 기관(HUG → HF → SGI, 열거형 순)의 guarantee_id, 없으면 NULL. insurance_eligible = HUG OR HF OR SGI
--   전세가율   (선순위 + 보증금) × 100 ÷ 시세, 소수 둘째 자리 HALF_UP — 정수 나눗셈 div(2·R·10000 + M, 2·M) ÷ 100 으로 정확히 낸다
--              (numeric 나눗셈 뒤 round 는 중간 자릿수에서 한 번 더 반올림할 수 있다). 999.99 초과는 999.99(MAX_STORED_LEASE_RATIO)
--   판정 불가  등기가 없거나 시세 ≤ 0 이면 앱은 예외로 판정 행을 남기지 않는다 — judgeable = FALSE, 계산값은 NULL
--
-- 가정
--   ignore_mock_ledger 기본 true — 운영의 external.building-ledger.mode 는 real 이다(.env.example 「운영은 real」, EXTERNAL_BUILDINGLEDGER_MODE).
--     앱은 real 일 때만 MOCK 대장을 대장 없음으로 본다(application.yml 기본은 mock — 로컬 · 테스트). 운영 기동 인자가 다르면 -v ignore_mock_ledger=false.
--   기준선 행이 판정된 시점의 대장 모드 · 기준값이 지금과 다르면 저장값과 갈린다 — 앱은 「등급 · 3사 · 전세가율이 같으면」
--     저장하지 않으므로(RiskAnalysis.sameConclusion) 뒤에 바뀐 입력은 재판정 전까지 저장값에 반영되지 않는다. 51 이 대장 출처별로 나눠 보인다.
--   유형 코드는 APARTMENT 만 아파트, 나머지는 OTHER(앱 houseType). 기관 · 등기 목적 값은 앱 열거형 이름 그대로라고 본다.
--   공백 문자 — Java strip() 은 Character.isWhitespace 집합(아래 gc_jstrip), PostgreSQL btrim 기본은 공백 하나뿐이라 집합을 적었다.
--   문자열 비교는 결정적 정렬 규칙에서의 = (바이트 동일)이라 Java equals 와 같다.
--
-- 효율 — 행마다 하위 조회를 하지 않는다. 대상(t)을 한 번 뽑아 두고 갑구 · 을구를 그 registry_id 집합으로 한 번씩 묶어(GROUP BY) 붙인다.
--   갑구 · 을구에는 t 의 registry_id 최솟값 ~ 최댓값 조건도 건다 — 새 매물은 구 단위로 등기 식별자를 연달아 받았으므로(20_bundle.sql)
--   property_id 구간이면 등기 식별자도 대체로 한 구간이라 1,000만 행 표를 다 읽지 않고 색인 범위로 끝난다.
--   기대는 색인 (마이그레이션 기준)
--     property PK (property_id)                                       — 범위 lo ~ hi
--     risk_analysis uq_risk_analysis_latest (property_id) WHERE is_latest — V9 · V19
--     building_registry uq_building_registry_property_id (property_id)   — V4
--     building_ledger uq_building_ledger_property_id (property_id)       — V5
--     ownership_history idx_ownership_history_registry (registry_id)     — V15
--     mortgage_history idx_mortgage_history_registry_active (registry_id) WHERE is_active — V19 (없으면 V15 idx_mortgage_history_registry)
--     loadtest.property_origin PK (property_id)                          — 00_stage.sql
--   property_code · guarantee_criteria · sgi_criteria · risk_criteria 는 수 행짜리 기준 표다.

\if :{?new_rows}
\else
  \set new_rows false
\endif
\if :{?lo}
\else
  \set lo 0
\endif
\if :{?hi}
\else
  \set hi 9223372036854775807
\endif
\if :{?ignore_mock_ledger}
\else
  \set ignore_mock_ledger true
\endif
\if :new_rows
  \set gc_scope 'EXISTS (SELECT 1 FROM loadtest.property_origin po WHERE po.property_id = p.property_id)'
\else
  \set gc_scope 'p.property_id <= (SELECT b.max_property_id FROM loadtest.baseline b)'
\endif

-- Java String.strip() — Character.isWhitespace: \t \n \x0B \f \r \x1C~\x1F, 공백 구분자(U+0020 · U+1680 · U+2000~2006 · U+2008~200A ·
-- U+205F · U+3000; 줄바꿈 없는 공백 U+00A0 · U+2007 · U+202F 는 제외), U+2028 · U+2029.
CREATE OR REPLACE FUNCTION pg_temp.gc_jstrip(s text) RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS
$$ SELECT btrim(s, E' \t\n\x0b\f\r\x1c\x1d\x1e\x1f            　  ') $$;

-- 한 기관의 가입 가능 — GuaranteeEligibilityJudge.judgeProvider 의 위배 조건이 하나도 없음. present = 그 기관 기준 행이 있음.
CREATE OR REPLACE FUNCTION pg_temp.gc_eligible(
    present boolean, collateral_ratio numeric, senior_limit numeric, max_deposit bigint, apartment_unlimited boolean,
    violation_disqualify boolean, right_violation_disqualify boolean,
    market numeric, deposit bigint, senior numeric, is_apartment boolean,
    violation boolean, right_violation boolean, owner_matched boolean, address_matched boolean)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT coalesce(present, false)
      AND NOT ((senior + deposit) * 100 > market * collateral_ratio)                              -- DEBT_RATIO_EXCEEDED
      AND NOT (senior_limit IS NOT NULL AND senior * 100 > market * senior_limit)                 -- SENIOR_DEBT_RATIO_EXCEEDED
      AND NOT (NOT (apartment_unlimited AND is_apartment) AND deposit > max_deposit)              -- DEPOSIT_LIMIT_EXCEEDED
      AND NOT (violation IS TRUE AND violation_disqualify)                                        -- VIOLATION_BUILDING
      AND NOT (right_violation AND right_violation_disqualify)                                    -- RIGHT_VIOLATION
      AND owner_matched                                                                           -- OWNER_MISMATCH
      AND address_matched IS DISTINCT FROM FALSE $$;                                              -- ADDRESS_MISMATCH

CREATE OR REPLACE TEMP VIEW gc_calc AS
WITH g AS (   -- 기관 기준. sgi_criteria 행이 없는 기관은 아파트 무제한 거짓(앱 snapshots)
    SELECT gc.*, coalesce((SELECT bool_or(s.apartment_unlimited_yn) FROM sgi_criteria s
                            WHERE s.guarantee_id = gc.guarantee_id), false) AS apartment_unlimited
      FROM guarantee_criteria gc
), crit AS (
    SELECT rc.negative_equity_ratio, rc.caution_lease_ratio,
           max(g.guarantee_id)            FILTER (WHERE g.provider = 'HUG') AS hug_id,
           max(g.collateral_ratio)        FILTER (WHERE g.provider = 'HUG') AS hug_cr,
           max(g.senior_debt_ratio_limit) FILTER (WHERE g.provider = 'HUG') AS hug_sl,
           max(g.max_deposit)             FILTER (WHERE g.provider = 'HUG') AS hug_md,
           bool_or(g.apartment_unlimited)           FILTER (WHERE g.provider = 'HUG') AS hug_au,
           bool_or(g.violation_disqualify_yn)       FILTER (WHERE g.provider = 'HUG') AS hug_vd,
           bool_or(g.right_violation_disqualify_yn) FILTER (WHERE g.provider = 'HUG') AS hug_rd,
           max(g.guarantee_id)            FILTER (WHERE g.provider = 'HF') AS hf_id,
           max(g.collateral_ratio)        FILTER (WHERE g.provider = 'HF') AS hf_cr,
           max(g.senior_debt_ratio_limit) FILTER (WHERE g.provider = 'HF') AS hf_sl,
           max(g.max_deposit)             FILTER (WHERE g.provider = 'HF') AS hf_md,
           bool_or(g.apartment_unlimited)           FILTER (WHERE g.provider = 'HF') AS hf_au,
           bool_or(g.violation_disqualify_yn)       FILTER (WHERE g.provider = 'HF') AS hf_vd,
           bool_or(g.right_violation_disqualify_yn) FILTER (WHERE g.provider = 'HF') AS hf_rd,
           max(g.guarantee_id)            FILTER (WHERE g.provider = 'SGI') AS sgi_id,
           max(g.collateral_ratio)        FILTER (WHERE g.provider = 'SGI') AS sgi_cr,
           max(g.senior_debt_ratio_limit) FILTER (WHERE g.provider = 'SGI') AS sgi_sl,
           max(g.max_deposit)             FILTER (WHERE g.provider = 'SGI') AS sgi_md,
           bool_or(g.apartment_unlimited)           FILTER (WHERE g.provider = 'SGI') AS sgi_au,
           bool_or(g.violation_disqualify_yn)       FILTER (WHERE g.provider = 'SGI') AS sgi_vd,
           bool_or(g.right_violation_disqualify_yn) FILTER (WHERE g.provider = 'SGI') AS sgi_rd
      FROM (SELECT * FROM risk_criteria ORDER BY risk_criteria_id ASC LIMIT 1) rc   -- 앱 findFirstByOrderByRiskCriteriaIdAsc
      CROSS JOIN g
     GROUP BY rc.negative_equity_ratio, rc.caution_lease_ratio
), t AS MATERIALIZED (   -- 대상: 범위 · 범위(기준선/새 매물) 안에서 최신 판정이 있는 매물
    SELECT p.property_id, p.district, p.deposit, p.market_price, p.landlord_name, pc.code_value AS property_type,
           r.registry_id, r.registry_address, r.exclusive_area AS registry_area,
           l.ledger_id AS ledger_row_id, l.data_source AS ledger_source,
           (l.ledger_id IS NOT NULL AND NOT (:'ignore_mock_ledger'::boolean AND l.data_source = 'MOCK')) AS ledger_used,
           l.ledger_address, l.exclusive_area AS ledger_area, l.violation_yn,
           ra.risk_id, ra.registry_id AS st_registry_id, ra.ledger_id AS st_ledger_id,
           ra.hug_eligible_yn AS st_hug, ra.hf_eligible_yn AS st_hf, ra.sgi_eligible_yn AS st_sgi,
           ra.insurance_eligible_yn AS st_insurance, ra.eligible_guarantee_id AS st_guarantee_id,
           ra.risk_grade AS st_grade, ra.risk_reason AS st_reason, ra.lease_ratio AS st_lease_ratio
      FROM property p
      JOIN risk_analysis ra ON ra.property_id = p.property_id AND ra.is_latest
      JOIN property_code pc ON pc.code_id = p.property_type_code_id
      LEFT JOIN building_registry r ON r.property_id = p.property_id
      LEFT JOIN building_ledger l ON l.property_id = p.property_id
     WHERE p.property_id BETWEEN :lo AND :hi
       AND :gc_scope
), own AS (   -- 갑구 한 번 묶기: 현재 소유자 · 권리 침해 유무
    SELECT o.registry_id,
           (array_agg(o.owner_name ORDER BY o.rank_no DESC, o.ownership_id ASC)
                FILTER (WHERE o.is_current AND o.right_type IN ('OWNERSHIP_PRESERVATION', 'OWNERSHIP_TRANSFER')))[1]
               AS current_owner,
           bool_or(o.is_current AND o.right_type IN ('SEIZURE', 'PROVISIONAL_SEIZURE', 'AUCTION_COMMENCEMENT', 'TRUST'))
               AS right_violation,
           -- 판정 근거(54_judgement.sql)용 — 말소 안 된 권리 침해 · 경고 목적. 순서는 54 가 열거형 선언 순으로 다시 편다
           array_agg(DISTINCT o.right_type) FILTER (WHERE o.is_current AND o.right_type IN
               ('SEIZURE', 'PROVISIONAL_SEIZURE', 'AUCTION_COMMENCEMENT', 'TRUST', 'PROVISIONAL_REGISTRATION',
                'TENANCY_REGISTRATION_ORDER')) AS current_rights
      FROM ownership_history o
     WHERE o.registry_id IN (SELECT registry_id FROM t)
       AND o.registry_id BETWEEN (SELECT min(registry_id) FROM t) AND (SELECT max(registry_id) FROM t)   -- 구간이면 색인 범위로 끝난다
     GROUP BY o.registry_id
), debt AS (  -- 을구 한 번 묶기: 선순위채권 합계
    SELECT h.registry_id, sum(h.max_bond_amount::numeric + coalesce(h.prior_tenant_deposit, 0)) AS senior_debt
      FROM mortgage_history h
     WHERE h.is_active AND h.senior_debt_yn
       AND h.registry_id IN (SELECT registry_id FROM t)
       AND h.registry_id BETWEEN (SELECT min(registry_id) FROM t) AND (SELECT max(registry_id) FROM t)
     GROUP BY h.registry_id
), inp AS (
    SELECT t.*, c.*,
           (t.registry_id IS NOT NULL AND t.market_price > 0) AS judgeable,
           CASE WHEN t.registry_id IS NOT NULL AND t.market_price > 0 THEN t.market_price::numeric END AS market,
           coalesce(d.senior_debt, 0) AS senior_debt,
           coalesce(d.senior_debt, 0) + t.deposit AS risk_amount,
           t.property_type = 'APARTMENT' AS is_apartment,
           ow.current_owner,
           coalesce(pg_temp.gc_jstrip(ow.current_owner) = pg_temp.gc_jstrip(t.landlord_name), false) AS owner_matched,
           coalesce(ow.right_violation, false) AS right_violation,
           coalesce(ow.current_rights, '{}') AS current_rights,
           CASE WHEN NOT t.ledger_used THEN NULL
                WHEN t.ledger_address IS NULL OR t.registry_address IS NULL THEN FALSE
                ELSE regexp_replace(pg_temp.gc_jstrip(t.ledger_address), E'[ \t\n\x0b\f\r]+', ' ', 'g')
                   = regexp_replace(pg_temp.gc_jstrip(t.registry_address), E'[ \t\n\x0b\f\r]+', ' ', 'g') END
               AS address_matched,
           CASE WHEN t.ledger_used THEN t.violation_yn END AS violation_building
      FROM t
      CROSS JOIN crit c
      LEFT JOIN own ow ON ow.registry_id = t.registry_id
      LEFT JOIN debt d ON d.registry_id = t.registry_id
), judged AS (
    SELECT i.*,
           i.risk_amount * 100 > i.market * i.negative_equity_ratio AS negative_equity,
           pg_temp.gc_eligible(i.hug_id IS NOT NULL, i.hug_cr, i.hug_sl, i.hug_md, i.hug_au, i.hug_vd, i.hug_rd, i.market,
                               i.deposit, i.senior_debt, i.is_apartment, i.violation_building, i.right_violation,
                               i.owner_matched, i.address_matched) AS c_hug,
           pg_temp.gc_eligible(i.hf_id IS NOT NULL, i.hf_cr, i.hf_sl, i.hf_md, i.hf_au, i.hf_vd, i.hf_rd, i.market,
                               i.deposit, i.senior_debt, i.is_apartment, i.violation_building, i.right_violation,
                               i.owner_matched, i.address_matched) AS c_hf,
           pg_temp.gc_eligible(i.sgi_id IS NOT NULL, i.sgi_cr, i.sgi_sl, i.sgi_md, i.sgi_au, i.sgi_vd, i.sgi_rd, i.market,
                               i.deposit, i.senior_debt, i.is_apartment, i.violation_building, i.right_violation,
                               i.owner_matched, i.address_matched) AS c_sgi,
           least(div(i.risk_amount * 20000 + i.market, 2 * i.market) / 100, 999.99)::numeric(5, 2) AS c_lease_ratio
      FROM inp i
), graded AS (
    SELECT j.*,
           (j.c_hug OR j.c_hf OR j.c_sgi) AS c_insurance,
           CASE WHEN j.c_hug THEN j.hug_id WHEN j.c_hf THEN j.hf_id WHEN j.c_sgi THEN j.sgi_id END AS c_guarantee_id,
           CASE WHEN j.negative_equity THEN 'DANGER'
                WHEN NOT (j.c_hug OR j.c_hf OR j.c_sgi) THEN 'DANGER'
                WHEN j.risk_amount * 100 > j.market * j.caution_lease_ratio THEN 'CAUTION'
                ELSE 'SAFE' END AS c_grade,
           CASE WHEN j.negative_equity THEN 'NEGATIVE_EQUITY'
                WHEN NOT (j.c_hug OR j.c_hf OR j.c_sgi) THEN 'INSURANCE_INELIGIBLE'
                WHEN j.risk_amount * 100 > j.market * j.caution_lease_ratio THEN 'LEASE_RATIO_CAUTION'
                ELSE 'INSURANCE_ELIGIBLE' END AS c_reason
      FROM judged j
)
SELECT property_id, district, risk_id, judgeable,
       -- 입력
       property_type, deposit, market_price, senior_debt, risk_amount, landlord_name, current_owner, owner_matched,
       right_violation, registry_id, st_registry_id, ledger_row_id, ledger_source, ledger_used, st_ledger_id,
       address_matched, violation_building, registry_area, ledger_area,
       -- 판정 근거(54_judgement.sql)용 입력 — 51_check · 52 는 쓰지 않는다
       is_apartment, current_rights, CASE WHEN judgeable THEN negative_equity END AS c_negative_equity,
       -- 저장값 · 계산값 (판정 불가면 계산값 NULL)
       st_hug,          CASE WHEN judgeable THEN c_hug END          AS c_hug,
       st_hf,           CASE WHEN judgeable THEN c_hf END           AS c_hf,
       st_sgi,          CASE WHEN judgeable THEN c_sgi END          AS c_sgi,
       st_insurance,    CASE WHEN judgeable THEN c_insurance END    AS c_insurance,
       st_guarantee_id, CASE WHEN judgeable THEN c_guarantee_id END AS c_guarantee_id,
       st_grade,        CASE WHEN judgeable THEN c_grade END        AS c_grade,
       st_reason,       CASE WHEN judgeable THEN c_reason END       AS c_reason,
       st_lease_ratio,  CASE WHEN judgeable THEN c_lease_ratio END  AS c_lease_ratio,
       -- 항목별 불일치 (판정 가능한 행만)
       judgeable AND st_hug <> c_hug                                    AS d_hug,
       judgeable AND st_hf <> c_hf                                      AS d_hf,
       judgeable AND st_sgi <> c_sgi                                    AS d_sgi,
       judgeable AND st_insurance <> c_insurance                        AS d_insurance,
       judgeable AND st_guarantee_id IS DISTINCT FROM c_guarantee_id    AS d_guarantee_id,
       judgeable AND st_grade IS DISTINCT FROM c_grade                  AS d_grade,
       judgeable AND st_reason IS DISTINCT FROM c_reason                AS d_reason,
       judgeable AND st_lease_ratio <> c_lease_ratio                    AS d_lease_ratio,
       judgeable AND (st_hug <> c_hug OR st_hf <> c_hf OR st_sgi <> c_sgi OR st_insurance <> c_insurance
                      OR st_guarantee_id IS DISTINCT FROM c_guarantee_id OR st_grade IS DISTINCT FROM c_grade
                      OR st_reason IS DISTINCT FROM c_reason OR st_lease_ratio <> c_lease_ratio) AS differs
  FROM graded;

\set gc_calc_loaded true
