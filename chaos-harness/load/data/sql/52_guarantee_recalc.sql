-- 보증 판정 보정 ② 전체 재계산 (#376). 새 매물(loadtest.property_origin)의 최신 판정을 앱 산식(51_guarantee_calc.inc.sql)으로
-- 다시 내고, 다른 행만 그 자리에서 고친다 — hug · hf · sgi · insurance_eligible_yn · eligible_guarantee_id · risk_grade ·
-- risk_reason · lease_ratio. 50_owner_mismatch.sql(보정 ①)이 고치지 않은 보증금 한도 · 선순위채권 비율 · 권리 침해 · 주소 ·
-- 위반건축물 · 대장 모드 차이까지 한 번에 맞춘다.
--
-- 제자리 갱신인 이유 — 앱이라면 결론이 바뀔 때 기존 최신 행을 이력으로 내리고 새 행을 넣는다(RiskAnalysisCommandService.record).
-- 이 행들은 시험용으로 지어낸 판정이고 사용자가 본 이력이 없다. 새 행을 넣으면 「등급 변화」 이력 · 알림 대상이 시험 데이터
-- 생성 규칙(30_history.sql)과 무관하게 생긴다. 그래서 이력(previous_grade · is_latest · analyzed_at)은 건드리지 않는다.
-- ledger_id 도 그대로 둔다 — real 모드 앱은 MOCK 대장을 쓴 판정의 ledger_id 를 NULL 로 남기지만 결론 칸이 아니다(51 이 참고로 센다).
--
-- 순서: 51(기준선) 불일치 0 확인 → 이것 → 51 new_rows=true 로 불일치 0 확인 → 알림(42_notification.sql). 알림은 최신 행의
-- previous_grade → risk_grade 변화를 읽으므로 이것보다 먼저 돌리면 고친 등급과 어긋난다.
--
-- 실행 (구간마다 한 트랜잭션 — 디스크 IO 한도 아래에서 나눠 돌린다. run.sh guarantee-recalc LO HI [STEP] 이 구간을 돌린다.
-- lo · hi 를 빼면 새 매물 전체가 한 트랜잭션이다 — 468만 행 · WAL 이 한 번에 나가므로 쓰지 않는다):
--   cat 51_guarantee_calc.inc.sql 52_guarantee_recalc.sql | psql -X -v ON_ERROR_STOP=1 -v new_rows=true -v lo=312667 -v hi=512666
\set ON_ERROR_STOP 1
\if :{?gc_calc_loaded}
\else
  \warn '51_guarantee_calc.inc.sql 을 앞에 붙여 실행한다 (cat 51_guarantee_calc.inc.sql 52_guarantee_recalc.sql | psql -v new_rows=true ...)'
  SELECT gc_calc_include_missing;
\endif
\if :new_rows
\else
  \warn '새 매물만 고친다 — -v new_rows=true 가 필요하다(기준선 매물은 앱이 판정한 행이라 고치지 않는다)'
  SELECT gc_recalc_new_rows_only;
\endif

BEGIN;
SET LOCAL lock_timeout = '5s';
WITH upd AS (
    UPDATE risk_analysis ra
       SET hug_eligible_yn       = c.c_hug,
           hf_eligible_yn        = c.c_hf,
           sgi_eligible_yn       = c.c_sgi,
           insurance_eligible_yn = c.c_insurance,
           eligible_guarantee_id = c.c_guarantee_id,
           risk_grade            = c.c_grade,
           risk_reason           = c.c_reason,
           lease_ratio           = c.c_lease_ratio
      FROM gc_calc c
     WHERE ra.risk_id = c.risk_id
       AND ra.is_latest
       AND ra.property_id > (SELECT max_property_id FROM loadtest.baseline)   -- 이중 안전장치: 기준선 행은 절대 고치지 않는다
       AND c.judgeable AND c.differs
    RETURNING c.st_grade AS old_grade, c.st_reason AS old_reason, ra.risk_grade AS new_grade, ra.risk_reason AS new_reason,
              concat(c.st_hug::int, c.st_hf::int, c.st_sgi::int) AS old_hug_hf_sgi,
              concat(ra.hug_eligible_yn::int, ra.hf_eligible_yn::int, ra.sgi_eligible_yn::int) AS new_hug_hf_sgi
)
SELECT old_grade, old_reason, new_grade, new_reason, count(*) AS corrected,
       count(*) FILTER (WHERE old_hug_hf_sgi <> new_hug_hf_sgi) AS changed_3sa
  FROM upd
 GROUP BY ROLLUP ((old_grade, old_reason, new_grade, new_reason))
 ORDER BY old_grade NULLS LAST, old_reason, new_grade, new_reason;
COMMIT;
