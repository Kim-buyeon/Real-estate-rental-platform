-- 보증 판정 보정 ① 소유자 불일치 (#376). 판정 묶음은 보증 3사 결과를 원본에서 복사했다(20_bundle.sql). 그런데 새 매물의
-- 등기 현재 소유자는 생성 규칙(임대인명, 의도적 불일치 20%)으로 바꿔 넣었으므로, 불일치 매물이 「가입 가능」으로 남을 수 있다.
-- 앱은 소유자 불일치면 3사 모두 가입 불가다(GuaranteeEligibilityJudge OWNER_MISMATCH) — 그 매물만 앱과 같은 값으로 고친다.
--   현재 소유자 = 현재 · 소유권 보존/이전 중 순위가 가장 높은 행(DocumentConsistencyChecker.currentOwner), 이름은 앞뒤 공백만 걷어 비교
--   등급 = 깡통전세면 NEGATIVE_EQUITY 를 유지, 아니면 DANGER · INSURANCE_INELIGIBLE (RiskGradeCalculator 순서)
-- 나머지 조건(보증금 한도 · 선순위채권 비율 · 권리 침해 · 주소)에서 원본과 갈리는 경우는 여기서 고치지 않는다 — 별도 보정.
\set ON_ERROR_STOP 1
BEGIN;
WITH owner AS (
    SELECT DISTINCT ON (o.registry_id) o.registry_id, btrim(o.owner_name) AS owner_name
      FROM ownership_history o
     WHERE o.is_current AND o.right_type IN ('OWNERSHIP_PRESERVATION', 'OWNERSHIP_TRANSFER')
       AND o.registry_id > (SELECT max_registry_id FROM loadtest.baseline)
     ORDER BY o.registry_id, o.rank_no DESC
), target AS (
    SELECT ra.risk_id
      FROM loadtest.property_origin po
      JOIN property p ON p.property_id = po.property_id
      JOIN risk_analysis ra ON ra.property_id = po.property_id AND ra.is_latest
      LEFT JOIN owner ow ON ow.registry_id = ra.registry_id
     WHERE ra.insurance_eligible_yn
       AND (ow.owner_name IS NULL OR ow.owner_name <> btrim(p.landlord_name))
 ), upd AS (
UPDATE risk_analysis ra
   SET hug_eligible_yn = FALSE, hf_eligible_yn = FALSE, sgi_eligible_yn = FALSE, insurance_eligible_yn = FALSE,
       eligible_guarantee_id = NULL,
       risk_grade = 'DANGER',
       risk_reason = CASE WHEN ra.risk_reason = 'NEGATIVE_EQUITY' THEN 'NEGATIVE_EQUITY' ELSE 'INSURANCE_INELIGIBLE' END
  FROM target t
 WHERE ra.risk_id = t.risk_id
RETURNING ra.risk_grade, ra.risk_reason
)
SELECT risk_grade, risk_reason, count(*) AS corrected FROM upd GROUP BY 1, 2;
COMMIT;
