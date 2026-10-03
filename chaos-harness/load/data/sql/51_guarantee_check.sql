-- 보증 3사 · 위험 등급 재현 확인 — 읽기 전용 (#376). 51_guarantee_calc.inc.sql 의 산식이 앱과 같은지 저장값과 대조한다.
--   기준선 이하 매물(new_rows=false, 기본)은 앱이 직접 판정한 행이다 — 불일치 0 이면 SQL = 앱. 0 이 아니면 52 를 돌리지 않는다.
--   새 매물(new_rows=true)에 돌리면 52 가 고칠 건수를 미리 보고, 52 뒤에 돌리면 0 이어야 한다.
-- 실행 (공용 계산부를 앞에 붙인다 — run.sh guarantee-check):
--   cat 51_guarantee_calc.inc.sql 51_guarantee_check.sql | psql -X -v ON_ERROR_STOP=1 [-v new_rows=true] [-v lo=1 -v hi=400000]
-- 쓰는 것은 세션 임시 함수 · 뷰 · 임시 표다. 운영 표는 읽기만 한다 — 큰 읽기(임시 표 만들기)는 READ ONLY 트랜잭션
-- 밖에서 돈다(CREATE TABLE AS 가 그 안에서 막힌다). 새 매물은 반드시 구간으로 나눈다(아래, run.sh guarantee-check-all).
\set ON_ERROR_STOP 1
\if :{?gc_calc_loaded}
\else
  \warn '51_guarantee_calc.inc.sql 을 앞에 붙여 실행한다 (cat 51_guarantee_calc.inc.sql 51_guarantee_check.sql | psql ...)'
  SELECT gc_calc_include_missing;
\endif

-- 계산을 한 번만 한다 — 뷰를 아래 세 질의가 각자 부르면 등기 · 소유 · 저당 · 대장 집계가 세 번 돈다(#376, 같은 표를
-- 되풀이해 읽어 디스크 크레딧을 쓴 90_verify 첫 판의 실수). 읽기 전용 트랜잭션 안에서는 CREATE TABLE AS 가 막힌다 —
-- 세션 임시 표를 트랜잭션 밖에서 만들고(운영 표는 쓰지 않는다) 대조는 READ ONLY 안에서 한다.
-- 임시 표는 행 수만큼 쓴다 — 새 매물(468만)은 -v lo/hi 로 나눠(50만 이하) 돌린다.
CREATE TEMP TABLE gc AS SELECT * FROM gc_calc;
BEGIN READ ONLY;
\echo '== 범위 · 대장 모드'
SELECT :'new_rows' AS new_rows, :lo AS lo, :hi AS hi, :'ignore_mock_ledger' AS ignore_mock_ledger;

\echo '== 항목별 불일치 (판정 가능한 행 기준, 전부 0 이어야 SQL = 앱)'
SELECT count(*)                                          AS rows_with_latest_analysis,
       count(*) FILTER (WHERE NOT judgeable)             AS not_judgeable,       -- 등기 없음 · 시세 ≤ 0 (앱은 판정 행을 남기지 않는다)
       count(*) FILTER (WHERE d_hug)                     AS hug,
       count(*) FILTER (WHERE d_hf)                      AS hf,
       count(*) FILTER (WHERE d_sgi)                     AS sgi,
       count(*) FILTER (WHERE d_insurance)               AS insurance_eligible,
       count(*) FILTER (WHERE d_guarantee_id)            AS eligible_guarantee_id,
       count(*) FILTER (WHERE d_grade)                   AS risk_grade,
       count(*) FILTER (WHERE d_reason)                  AS risk_reason,
       count(*) FILTER (WHERE d_lease_ratio)             AS lease_ratio,
       count(*) FILTER (WHERE differs)                   AS any_field,
       -- 참고(불일치로 세지 않는다): 판정 행이 가리키는 등기 · 대장이 앱이 지금 고를 것과 다른 건수
       count(*) FILTER (WHERE judgeable AND st_registry_id IS DISTINCT FROM registry_id)  AS info_registry_ref_differs,
       count(*) FILTER (WHERE judgeable AND st_ledger_id IS DISTINCT FROM
                              CASE WHEN ledger_used THEN ledger_row_id END)              AS info_ledger_ref_differs
  FROM gc;

\echo '== 불일치의 대장 출처별 분포 (판정 시점 대장 모드 · 대장 교체가 원인인지 가른다)'
SELECT coalesce(ledger_source, '(없음)') AS ledger_source, ledger_used,
       (st_ledger_id IS NOT DISTINCT FROM CASE WHEN ledger_used THEN ledger_row_id END) AS stored_ledger_ref_same,
       count(*) AS rows, count(*) FILTER (WHERE differs) AS differs,
       count(*) FILTER (WHERE d_hug OR d_hf OR d_sgi) AS differs_3sa, count(*) FILTER (WHERE d_grade) AS differs_grade
  FROM gc
 WHERE judgeable
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3;

\echo '== 불일치 표본 (최대 20건, 입력 전부)'
SELECT property_id, risk_id, property_type, deposit, market_price, senior_debt, landlord_name, current_owner,
       owner_matched, right_violation, ledger_source, ledger_used, address_matched, violation_building,
       st_hug || '/' || st_hf || '/' || st_sgi       AS st_hug_hf_sgi,
       c_hug || '/' || c_hf || '/' || c_sgi          AS c_hug_hf_sgi,
       st_guarantee_id, c_guarantee_id, st_grade, c_grade, st_reason, c_reason, st_lease_ratio, c_lease_ratio,
       st_registry_id, registry_id, st_ledger_id, ledger_row_id
  FROM gc
 WHERE differs
 ORDER BY property_id
 LIMIT 20;
ROLLBACK;
DROP TABLE gc;
