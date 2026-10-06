-- 판정 이력 (#376 · INF-06 #390) — 이번에 넣은 매물마다 이전 판정 0 · 1 · 2건(33 · 40 · 27% — 평균 0.94).
-- 0.94 는 운영 기준선의 비율이다 — 판정 605,606행 − 최신 312,661행 = 이력 292,945 ÷ 매물 312,661 = 0.937(2026-10-05 pg_stat_user_tables).
-- 54_judgement.sql(결론 · 근거) 뒤에 돈다 — 이력 등급이 고친 최신 등급과 달라야 한다. 이력 행의 근거 · 지문은 NULL(V21 — 이력은 읽지 않는다).
-- 이력은 최신 판정 행을 본으로 등급만 다르게 둔다. 등급 · 사유 짝은 앱의 GradeReason 과 같다.
-- 최신 행의 previous_grade 는 마지막 이력의 등급으로 잇는다(앱 RiskAnalysis.record 의 previousGrade).
-- 실행: psql -v ON_ERROR_STOP=1 -v district=종로구 -f 30_history.sql
\set ON_ERROR_STOP 1
BEGIN;

CREATE TEMP TABLE hist ON COMMIT DROP AS
WITH latest AS (
    SELECT ra.*, (hashint8(ra.property_id) & 2147483647) % 100 AS draw
      FROM loadtest.property_origin o
      JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
     WHERE o.district = :'district' AND o.bundled AND ra.previous_grade IS NULL
       AND NOT EXISTS (SELECT 1 FROM risk_analysis h WHERE h.property_id = o.property_id AND NOT h.is_latest)
), counted AS (
    SELECT l.*, CASE WHEN draw < 33 THEN 0 WHEN draw < 73 THEN 1 ELSE 2 END AS k FROM latest l
)
SELECT c.risk_id AS latest_risk_id, c.property_id, c.registry_id, c.ledger_id, c.eligible_guarantee_id,
       c.lease_ratio, c.hug_eligible_yn, c.hf_eligible_yn, c.sgi_eligible_yn, c.insurance_eligible_yn,
       c.risk_grade AS latest_grade, c.analyzed_at AS latest_at, s.step, c.k,
       -- 단계별 등급: 최신 등급과 다르게(바로 앞 이력), 그 앞은 자유롭게
       (ARRAY['SAFE', 'CAUTION', 'DANGER'])[1 + ((hashint8(c.property_id * 7 + s.step) & 2147483647) % 3)] AS draw_grade
  FROM counted c CROSS JOIN LATERAL generate_series(1, c.k) AS s(step);

CREATE INDEX ON hist (property_id, step);
ANALYZE hist;

-- step 1 = 최신 바로 앞. 최신과 같은 등급이 나오면 한 칸 돌린다 — 변화가 없는 이력은 앱이 남기지 않는다
UPDATE hist SET draw_grade = CASE latest_grade WHEN 'SAFE' THEN 'CAUTION' WHEN 'CAUTION' THEN 'DANGER' ELSE 'SAFE' END
 WHERE step = 1 AND draw_grade = latest_grade;
UPDATE hist h SET draw_grade = CASE p.draw_grade WHEN 'SAFE' THEN 'CAUTION' WHEN 'CAUTION' THEN 'DANGER' ELSE 'SAFE' END
  FROM hist p
 WHERE h.step = 2 AND p.property_id = h.property_id AND p.step = 1 AND h.draw_grade = p.draw_grade;

INSERT INTO risk_analysis (property_id, registry_id, ledger_id, eligible_guarantee_id, lease_ratio, hug_eligible_yn,
                           hf_eligible_yn, sgi_eligible_yn, insurance_eligible_yn, risk_grade, previous_grade,
                           risk_reason, is_latest, analyzed_at)
SELECT h.property_id, h.registry_id, h.ledger_id, h.eligible_guarantee_id, h.lease_ratio, h.hug_eligible_yn,
       h.hf_eligible_yn, h.sgi_eligible_yn, h.insurance_eligible_yn, h.draw_grade,
       older.draw_grade,          -- 한 칸 더 앞 이력의 등급(없으면 NULL) — 조인으로 붙인다(상관 서브쿼리는 행 수 제곱)
       CASE h.draw_grade WHEN 'SAFE' THEN 'INSURANCE_ELIGIBLE' WHEN 'CAUTION' THEN 'LEASE_RATIO_CAUTION'
                         ELSE CASE WHEN h.insurance_eligible_yn THEN 'NEGATIVE_EQUITY' ELSE 'INSURANCE_INELIGIBLE' END END,
       FALSE,
       h.latest_at - make_interval(days => 30 * h.step + (hashint8(h.property_id + h.step) & 2147483647)::INT % 30)
  FROM hist h
  LEFT JOIN hist older ON older.property_id = h.property_id AND older.step = h.step + 1;

UPDATE risk_analysis ra SET previous_grade = h.draw_grade
  FROM hist h WHERE h.step = 1 AND ra.risk_id = h.latest_risk_id;

SELECT k, count(DISTINCT property_id) FROM hist GROUP BY k ORDER BY k;
COMMIT;
