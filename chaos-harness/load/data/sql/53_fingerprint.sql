-- 판정 기준 지문 (INF-06 · #390, V21 criteria_fingerprint) — 54_judgement.sql 이 새 최신 판정 행에 적을 지문을 한 번 계산해 둔다.
--
-- 지문은 매물과 무관하다 — 기준표 6종 · 대장 연동 모드만으로 정해진다(JudgementCriteriaCache.load → CriteriaFingerprintCalculator).
-- 앱과 같은 정규 문자열을 SQL 로 펴서 SHA-256 16진 소문자로 줄인다. 줄은 \n 으로 잇는다.
--   format=1                                       FORMAT_VERSION
--   buildingLedgerMode=<모드>                       external.building-ledger.mode — 운영 real(.env.example)
--   risk=<깡통전세 선>,<CAUTION 경계>                 risk_criteria 의 risk_criteria_id 최소 행
--   기관마다(HUG → HF → SGI, 열거형 순 — 기준 행이 있는 기관만)
--     guarantee=<기관>,<guarantee_id>,<담보인정비율>,<선순위 한도|->,<최대 보증금>,<아파트 무제한>,<위반건축물 불가>,<권리 침해 불가>,
--               <대출 연계>,<상품명 길이:상품명|->
--     rate=<기관>,<주택유형>,<보증금 하한>,<보증금 상한|->,<전세가율 하한>,<전세가율 상한>,<요율>   (premium_rate_id 순)
--   소수는 BigDecimal.stripTrailingZeros().toPlainString() — PostgreSQL trim_scale(13 이상)과 같다(90.00 → 90, 0.000 → 0).
--   불리언은 Java Boolean.toString · PostgreSQL boolean::text 둘 다 true/false. 상품명 길이는 UTF-16 단위 — 한글은 BMP 라 char_length 와 같다.
--   아파트 무제한 = sgi_criteria 행이 있고 참, 대출 연계 = hf_criteria 행이 있고 참, 상품 = insurance_product 의 insurance_id 가 가장 작은 행.
--
-- 대조 — 운영 최신 판정 행에 앱이 적은 지문이 있으면(V21 배포 뒤 조회된 매물) 가장 많은 값과 같아야 한다. 다르면 멈춘다 —
-- 산식이나 모드가 앱과 다르다는 뜻이고, 그대로 적으면 위험도 조회가 전부 다시 판정한다(시험이 쓰기 경로를 잰다).
-- 앱이 적은 지문이 하나도 없으면 계산값만으로 간다 — 이때는 README 「확인」의 조회 스모크로 저장된 판정이 쓰이는지 본다.
-- 실행: psql -v ON_ERROR_STOP=1 [-v ledger_mode=real] -f 53_fingerprint.sql → loadtest.criteria_fp(1행)
\set ON_ERROR_STOP 1
\if :{?ledger_mode}
\else
  \set ledger_mode real
\endif

DROP TABLE IF EXISTS loadtest.criteria_fp;
CREATE TABLE loadtest.criteria_fp AS
WITH g AS (
    SELECT gc.*,
           CASE gc.provider WHEN 'HUG' THEN 1 WHEN 'HF' THEN 2 WHEN 'SGI' THEN 3 END AS ord,
           coalesce((SELECT s.apartment_unlimited_yn FROM sgi_criteria s WHERE s.guarantee_id = gc.guarantee_id
                      ORDER BY s.sgi_criteria_id LIMIT 1), false) AS apartment_unlimited,
           coalesce((SELECT h.loan_linked_required_yn FROM hf_criteria h WHERE h.guarantee_id = gc.guarantee_id
                      ORDER BY h.hf_criteria_id LIMIT 1), false) AS loan_link,
           (SELECT ip.product_name FROM insurance_product ip WHERE ip.guarantee_id = gc.guarantee_id
             ORDER BY ip.insurance_id LIMIT 1) AS product_name
      FROM guarantee_criteria gc
), lines AS (
    SELECT g.ord,
           'guarantee=' || g.provider || ',' || g.guarantee_id || ',' || trim_scale(g.collateral_ratio) || ','
               || coalesce(trim_scale(g.senior_debt_ratio_limit)::text, '-') || ',' || g.max_deposit || ','
               || g.apartment_unlimited::text || ',' || g.violation_disqualify_yn::text || ','
               || g.right_violation_disqualify_yn::text || ',' || g.loan_link::text || ','
               || coalesce(char_length(g.product_name) || ':' || g.product_name, '-')
               || coalesce((SELECT string_agg(E'\nrate=' || g.provider || ',' || r.house_type || ',' || r.deposit_min || ','
                                              || coalesce(r.deposit_max::text, '-') || ',' || trim_scale(r.debt_ratio_min) || ','
                                              || trim_scale(r.debt_ratio_max) || ',' || trim_scale(r.premium_rate),
                                              '' ORDER BY r.premium_rate_id)
                              FROM guarantee_premium_rate r WHERE r.guarantee_id = g.guarantee_id), '') AS line
      FROM g
), canon AS (
    SELECT 'format=1' || E'\n' || 'buildingLedgerMode=' || :'ledger_mode' || E'\n'
               || 'risk=' || trim_scale(rc.negative_equity_ratio) || ',' || trim_scale(rc.caution_lease_ratio)
               || coalesce((SELECT string_agg(E'\n' || l.line, '' ORDER BY l.ord) FROM lines l), '') AS canonical
      FROM (SELECT * FROM risk_criteria ORDER BY risk_criteria_id LIMIT 1) rc
)
SELECT encode(sha256(convert_to(canonical, 'UTF8')), 'hex') AS fingerprint, canonical, now() AS computed_at
  FROM canon;

-- 앱이 적은 지문과 대조 — 판정 표를 한 번 훑는다(최신 행 부분 인덱스 + 지문 열은 인덱스에 없다. 61만 행 · 116 MB, 한 번만)
DROP TABLE IF EXISTS loadtest.criteria_fp_seen;
CREATE TABLE loadtest.criteria_fp_seen AS
SELECT criteria_fingerprint AS fingerprint, count(*) AS latest_rows
  FROM risk_analysis WHERE is_latest AND criteria_fingerprint IS NOT NULL GROUP BY 1;

SELECT f.fingerprint AS computed, s.fingerprint AS app_written, s.latest_rows,
       (s.fingerprint IS NULL OR s.fingerprint = f.fingerprint) AS ok
  FROM loadtest.criteria_fp f
  LEFT JOIN LATERAL (SELECT * FROM loadtest.criteria_fp_seen ORDER BY latest_rows DESC LIMIT 1) s ON TRUE;

-- 멈춤은 \if 로 한다 — CASE 안의 상수 1/0 은 플래너가 미리 계산해 조건과 무관하게 터진다
SELECT coalesce((SELECT fingerprint FROM loadtest.criteria_fp_seen ORDER BY latest_rows DESC LIMIT 1)
                <> (SELECT fingerprint FROM loadtest.criteria_fp), false) AS fp_mismatch \gset
\if :fp_mismatch
  \warn '계산한 지문이 앱이 적은 지문과 다르다 — loadtest.criteria_fp.canonical 을 기준표와 맞대 본다. 이대로 적으면 조회가 전부 다시 판정한다'
  SELECT fingerprint_must_match_app;
\endif
