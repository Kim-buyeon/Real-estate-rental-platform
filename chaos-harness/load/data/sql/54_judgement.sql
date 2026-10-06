-- 판정 결론 · 근거 · 지문 쓰기 (INF-06 · #390, V21 · V22) — 51_guarantee_calc.inc.sql 을 앞에 붙여 property_id 구간마다 돈다.
--
-- 위험도 · 대출 한도 조회는 최신 판정 행에 근거 JSON(judgement_snapshot)이 있고, 지문(criteria_fingerprint)이 지금 기준의 지문과
-- 같고, 매물이 재분석 대기(V18)가 아니면 판정하지 않고 저장된 근거를 돌려준다(RiskAnalysisCommandService.findStoredJudgement).
-- 하나라도 어긋나면 조회마다 등기 · 대장 · 기준표를 읽고 판정 표에 쓴다 — 시험이 읽기가 아니라 쓰기 경로를 잰다.
--
-- new_rows=true (새 매물, 구 하나씩 — run.sh district 가 부른다)
--   최신 판정 행의 결론(3사 · 가입 기관 · 등급 · 사유 · 전세가율)을 앱 산식(51)으로 다시 쓰고(52 와 같은 결론), 대장 참조를 앱처럼
--   real 모드의 MOCK 대장이면 NULL 로, 근거 JSON · 지문을 적는다. 그 뒤 매물의 최신 판정 비정규화 열(V22 risk_grade · lease_ratio)을
--   최신 행 값으로 맞춘다(V23 과 같은 문장, 구간만 좁힌다). 이력(30_history.sql)은 이 뒤에 — 이력 등급이 고친 등급과 어긋나지 않게.
-- new_rows=false (기준선 매물 — run.sh snapshot-baseline)
--   결론은 건드리지 않는다. 결론이 앱 산식과 같은 행(differs = false)만, 근거가 비어 있을 때 근거 · 지문을 적는다 — 앱의
--   「결론 같음 → recordJudgement」와 같다. 결론이 다른 행은 비워 둔다(앱이 조회 때 다시 판정한다 — 건수를 낸다).
--
-- 근거 JSON 은 RiskResponse.Judgement 의 레코드 순서 · 이름 그대로, 공백 없이 쓴다(앱 JsonMapper 출력과 같은 모양을 노린다 —
-- 앱은 읽기만 하므로 순서 · 공백이 달라도 읽힌다. 같게 둔 것은 비교 편의).
--   debtRatio        (선순위 + 보증금) × 100 ÷ 시세, 소수 둘째 자리 HALF_UP, 상한 없음(NegativeEquityCalculator — 행의 lease_ratio 와 달리
--                    999.99 로 자르지 않는다)
--   providers[]      기관마다(HUG → HF → SGI) GuaranteeEligibilityJudge.judgeProvider — 위배 조건(선언 순), 대출 연계(hf_criteria),
--                    보증한도 = 시세 × 담보인정비율 ÷ 100 원 단위 버림 − 선순위, 0 미만 0, 예상 보증료 = 가입 가능일 때 맞는 첫 요율 행
--                    (premium_rate_id 순, 주택유형 · 보증금 구간 · 전세가율 구간)의 보증금 × 요율 ÷ 100 HALF_UP, 상품명 = 가입 가능일 때만
--   personalConditions  늘 6개 전부(PersonalCondition 선언 순)
--   rightViolations · warnings  말소 안 된 갑구의 목적 중 침해 · 경고 분류(OwnershipRightType 선언 순, 중복 없음)
--   consistency      명의 일치, 대장을 쓸 때만 주소 일치 · 위반건축물 · 면적 일치(값 비교), 아니면 셋 다 null
-- 정수 나눗셈(div)으로 반올림 · 버림을 정확히 낸다 — numeric 나눗셈 뒤 round 는 중간 자릿수에서 한 번 더 반올림할 수 있다(51 머리 주석).
--
-- 실행 (지문은 53_fingerprint.sql 이 먼저 loadtest.criteria_fp 에 둔다):
--   cat 51_guarantee_calc.inc.sql 54_judgement.sql | psql -X -v ON_ERROR_STOP=1 -v new_rows=true -v lo=312667 -v hi=340000
\set ON_ERROR_STOP 1
\if :{?gc_calc_loaded}
\else
  \warn '51_guarantee_calc.inc.sql 을 앞에 붙여 실행한다 (cat 51_guarantee_calc.inc.sql 54_judgement.sql | psql ...)'
  SELECT gc_calc_include_missing;
\endif
SELECT fingerprint AS fp FROM loadtest.criteria_fp \gset

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL work_mem = '32MB';

CREATE TEMP TABLE js ON COMMIT DROP AS
WITH g AS (
    SELECT gc.guarantee_id, gc.provider, gc.collateral_ratio AS cr, gc.senior_debt_ratio_limit AS sl, gc.max_deposit AS md,
           gc.violation_disqualify_yn AS vd, gc.right_violation_disqualify_yn AS rd,
           CASE gc.provider WHEN 'HUG' THEN 1 WHEN 'HF' THEN 2 WHEN 'SGI' THEN 3 END AS ord,
           coalesce((SELECT s.apartment_unlimited_yn FROM sgi_criteria s WHERE s.guarantee_id = gc.guarantee_id
                      ORDER BY s.sgi_criteria_id LIMIT 1), false) AS au,
           coalesce((SELECT h.loan_linked_required_yn FROM hf_criteria h WHERE h.guarantee_id = gc.guarantee_id
                      ORDER BY h.hf_criteria_id LIMIT 1), false) AS ll,
           (SELECT ip.product_name FROM insurance_product ip WHERE ip.guarantee_id = gc.guarantee_id
             ORDER BY ip.insurance_id LIMIT 1) AS pn
      FROM guarantee_criteria gc
), c AS MATERIALIZED (
    SELECT * FROM gc_calc WHERE judgeable
), pv AS (
    SELECT c.risk_id, g.ord, g.provider, g.ll, g.pn,
           concat_ws(',',
               CASE WHEN c.risk_amount * 100 > c.market_price * g.cr THEN '"DEBT_RATIO_EXCEEDED"' END,
               CASE WHEN g.sl IS NOT NULL AND c.senior_debt * 100 > c.market_price * g.sl
                    THEN '"SENIOR_DEBT_RATIO_EXCEEDED"' END,
               CASE WHEN NOT (g.au AND c.is_apartment) AND c.deposit > g.md THEN '"DEPOSIT_LIMIT_EXCEEDED"' END,
               CASE WHEN c.violation_building IS TRUE AND g.vd THEN '"VIOLATION_BUILDING"' END,
               CASE WHEN c.right_violation AND g.rd THEN '"RIGHT_VIOLATION"' END,
               CASE WHEN NOT c.owner_matched THEN '"OWNER_MISMATCH"' END,
               CASE WHEN c.address_matched IS FALSE THEN '"ADDRESS_MISMATCH"' END) AS failed,
           greatest(div(c.market_price::numeric * g.cr * 100, 10000) - c.senior_debt, 0) AS glimit,
           (SELECT div(c.deposit * r.premium_rate * 1000 + 50000, 100000)
              FROM guarantee_premium_rate r
             WHERE r.guarantee_id = g.guarantee_id
               AND r.house_type = CASE WHEN c.is_apartment THEN 'APARTMENT' ELSE 'OTHER' END
               AND c.deposit >= r.deposit_min AND (r.deposit_max IS NULL OR c.deposit <= r.deposit_max)
               AND CASE WHEN r.debt_ratio_min = 0 THEN c.risk_amount * 100 >= c.market_price * r.debt_ratio_min
                        ELSE c.risk_amount * 100 > c.market_price * r.debt_ratio_min END
               AND c.risk_amount * 100 <= c.market_price * r.debt_ratio_max
             ORDER BY r.premium_rate_id LIMIT 1) AS premium
      FROM c CROSS JOIN g
), pj AS (
    SELECT risk_id,
           string_agg('{"provider":"' || provider || '","eligible":' || (failed = '')::text
                      || ',"failedConditions":[' || failed || '],"loanLinkRequired":' || ll::text
                      || ',"guaranteeLimit":' || glimit::text
                      || ',"estimatedPremium":' || coalesce(CASE WHEN failed = '' THEN premium::text END, 'null')
                      || ',"productName":' || coalesce(CASE WHEN failed = '' THEN to_json(pn)::text END, 'null') || '}',
                      ',' ORDER BY ord) AS providers,
           -- 근거의 기관별 가입 가능이 51 의 결론과 같은지 — 다르면 이 파일의 위배 조건이 51 과 어긋난 것(아래에서 센다)
           bool_or(provider = 'HUG' AND failed = '') AS e_hug, bool_or(provider = 'HF' AND failed = '') AS e_hf,
           bool_or(provider = 'SGI' AND failed = '') AS e_sgi
      FROM pv GROUP BY risk_id
)
SELECT c.risk_id, c.property_id, c.differs, c.c_hug, c.c_hf, c.c_sgi, c.c_insurance, c.c_guarantee_id, c.c_grade,
       c.c_reason, c.c_lease_ratio, CASE WHEN c.ledger_used THEN c.ledger_row_id END AS c_ledger_id,
       (pj.e_hug IS DISTINCT FROM c.c_hug OR pj.e_hf IS DISTINCT FROM c.c_hf OR pj.e_sgi IS DISTINCT FROM c.c_sgi) AS e_differs,
       '{"riskGrade":"' || c.c_grade || '","gradeReason":"' || c.c_reason
       || '","debtRatio":' || (div(c.risk_amount * 20000 + c.market_price, 2 * c.market_price::numeric) / 100)::numeric(20, 2)::text
       || ',"seniorDebtTotal":' || c.senior_debt::bigint::text
       || ',"isNegativeEquity":' || c.c_negative_equity::text
       || ',"insuranceEligible":' || c.c_insurance::text
       || ',"providers":[' || coalesce(pj.providers, '') || ']'
       || ',"personalConditions":["ANNUAL_INCOME","APPLICATION_DEADLINE","NEW_OR_RENEWAL","RESIDENTIAL_USE_NOTATION",'
       || '"BROKER_CONTRACT","MOVE_IN_AND_FIXED_DATE"]'
       || ',"rightViolations":[' || coalesce((SELECT string_agg('"' || u.t || '"', ',' ORDER BY u.o)
                                                FROM unnest(ARRAY['SEIZURE', 'PROVISIONAL_SEIZURE', 'AUCTION_COMMENCEMENT', 'TRUST'])
                                                     WITH ORDINALITY AS u(t, o)
                                               WHERE u.t = ANY (c.current_rights)), '') || ']'
       || ',"warnings":[' || coalesce((SELECT string_agg('"' || u.t || '"', ',' ORDER BY u.o)
                                         FROM unnest(ARRAY['PROVISIONAL_REGISTRATION', 'TENANCY_REGISTRATION_ORDER'])
                                              WITH ORDINALITY AS u(t, o)
                                        WHERE u.t = ANY (c.current_rights)), '') || ']'
       || ',"consistency":{"ownerNameMatched":' || c.owner_matched::text
       || ',"addressMatched":' || coalesce(c.address_matched::text, 'null')
       || ',"violationBuilding":' || coalesce(c.violation_building::text, 'null')
       || ',"areaMatched":' || CASE WHEN NOT c.ledger_used THEN 'null'
                                    ELSE (c.ledger_area IS NOT NULL AND c.registry_area IS NOT NULL
                                          AND c.ledger_area = c.registry_area)::text END
       || '}}' AS snapshot
  FROM c LEFT JOIN pj ON pj.risk_id = c.risk_id;

-- 근거와 결론이 어긋나면 멈춘다(산식 두 벌이 갈렸다)
SELECT count(*) AS judged_rows, count(*) FILTER (WHERE differs) AS conclusion_differs,
       count(*) FILTER (WHERE e_differs) AS snapshot_vs_conclusion
  FROM js;
SELECT count(*) FILTER (WHERE e_differs) > 0 AS js_bad FROM js \gset
\if :js_bad
  \warn '근거 JSON 의 기관별 가입 가능이 51 의 결론과 다르다 — 54 의 위배 조건을 51 gc_eligible 과 맞대 본다'
  SELECT snapshot_vs_conclusion_must_agree;
\endif

\if :new_rows
WITH upd AS (
    UPDATE risk_analysis ra
       SET hug_eligible_yn = j.c_hug, hf_eligible_yn = j.c_hf, sgi_eligible_yn = j.c_sgi,
           insurance_eligible_yn = j.c_insurance, eligible_guarantee_id = j.c_guarantee_id,
           risk_grade = j.c_grade, risk_reason = j.c_reason, lease_ratio = j.c_lease_ratio, ledger_id = j.c_ledger_id,
           judgement_snapshot = j.snapshot, criteria_fingerprint = :'fp'
      FROM js j
     WHERE ra.risk_id = j.risk_id AND ra.is_latest
       AND ra.property_id > (SELECT max_property_id FROM loadtest.baseline)   -- 기준선 행은 결론을 고치지 않는다
       AND (ra.hug_eligible_yn, ra.hf_eligible_yn, ra.sgi_eligible_yn, ra.insurance_eligible_yn, ra.eligible_guarantee_id,
            ra.risk_grade, ra.risk_reason, ra.lease_ratio, ra.ledger_id, ra.judgement_snapshot, ra.criteria_fingerprint)
           IS DISTINCT FROM
           (j.c_hug, j.c_hf, j.c_sgi, j.c_insurance, j.c_guarantee_id, j.c_grade, j.c_reason, j.c_lease_ratio, j.c_ledger_id,
            j.snapshot, :'fp')
    RETURNING 1)
SELECT count(*) AS new_latest_rows_written FROM upd;
\else
WITH upd AS (
    UPDATE risk_analysis ra
       SET judgement_snapshot = j.snapshot, criteria_fingerprint = :'fp'
      FROM js j
     WHERE ra.risk_id = j.risk_id AND ra.is_latest AND NOT j.differs
       AND ra.property_id <= (SELECT max_property_id FROM loadtest.baseline)
       AND ra.judgement_snapshot IS NULL
    RETURNING 1)
SELECT count(*) AS baseline_snapshots_written,
       (SELECT count(*) FROM js WHERE differs) AS baseline_left_for_app_rejudge
  FROM upd;
\endif

-- 매물 최신 판정 비정규화 열(V22) — V23 과 같은 문장을 구간 · 범위로 좁혀서
WITH upd AS (
    UPDATE property p
       SET risk_grade = ra.risk_grade, lease_ratio = ra.lease_ratio
      FROM risk_analysis ra
     WHERE ra.property_id = p.property_id AND ra.is_latest
       AND p.property_id BETWEEN :lo AND :hi
       AND :gc_scope
       AND (p.risk_grade, p.lease_ratio) IS DISTINCT FROM (ra.risk_grade, ra.lease_ratio)
    RETURNING 1)
SELECT count(*) AS property_latest_columns_written FROM upd;
COMMIT;
