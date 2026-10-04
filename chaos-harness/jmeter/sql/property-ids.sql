-- 매물 ID 표본 — endpoints.jmx 의 property_ids CSV(INF-06 #390). 읽기 전용(READ ONLY 트랜잭션), 출력은 CSV 하나.
--
-- **EXPLAIN 먼저.** 운영 DB 에서 아래 COPY 를 돌리기 전에 COPY ( … ) 안의 SELECT 를 `EXPLAIN` 으로(ANALYZE 없이) 보고,
-- property 를 한 번만 훑는지(Sample Scan on property 하나) · 나머지 표는 인덱스 조회(uq_risk_analysis_latest ·
-- uq_building_registry_property_id · uq_building_ledger_property_id · property_code 기본 키)인지 확인한다.
--
--   실행(LOAD-01 또는 운영 PC — 접속은 운영 절차서대로. 결과를 계정 CSV 와 같은 폴더에 둔다):
--     psql -X -q -v ON_ERROR_STOP=1 [-v per_district=200] [-v pct=10] -f chaos-harness/jmeter/sql/property-ids.sql \
--       > <ACCOUNTS_DIR>/property-ids.csv
--   표본이 같은 결과를 내야 회차끼리 비교된다 — REPEATABLE 의 씨앗과 md5 순서로 고정했다(데이터가 바뀌지 않으면 같은 ID).
--
-- 열(머리 줄 있음): property_id, district, tier, latitude, longitude, property_type, insurance_eligible
--   tier — top(매물 수 상위 3개 자치구) | rest. 트래픽 정의서 3.3 「상위 3개 자치구 60% · 나머지 22개 40%」의 두 묶음이다.
--          문서가 상위 3개가 어느 구인지 정하지 않아 표본 안 매물 수 순위로 고른다(같은 수면 이름순).
--          60 : 40 은 여기가 아니라 플랜이 요청마다 묶음을 고를 때 건다(ENDPOINTS.md).
--   insurance_eligible — 최신 판정의 3사 가입 가능 여부. loans/limit 은 이것이 참인 매물만 쓴다(거짓이면 422
--          LOAN_PROPERTY_NOT_ELIGIBLE — API 명세서(대출) 1.1)
--
-- 고르는 조건 — 최신 판정(risk_analysis.is_latest) · 등기(building_registry) · 대장(building_ledger)이 모두 있는 매물.
--   위험도 · 대출 한도 · 대장 · 등기 조회는 등기 · 대장이 없으면 그 요청 안에서 외부 API 를 부른다(collectIfAbsent —
--   RegistryCommandService · LedgerCommandService). 외부 호출이 섞이면 서버 성능이 아니라 연동 지연을 재게 된다.
--   좌표가 없는 매물은 지도 3단계(반경)의 중심으로 쓸 수 없어 뺀다.
--
-- 개수 — 자치구마다 per_district(기본 200)건 → 최대 25 × 200 = 5,000건. 근거: 트래픽 정의서 7장 「데이터 편중 — 항상 같은
--   매물만 조회하면 캐시 적중률이 100% 가 되어 DB 부하가 사라진다」. 상위 묶음은 요청의 60% 를 3개 구 600건에 나누므로
--   100 RPS 의 상세 · 위험도 조회(트래픽 정의서 3.1 의 23%)도 매물 하나가 초당 0.03번 남짓만 다시 불린다.
--   표본이 작은 구(대장이 붙은 매물이 적은 구)는 200건이 안 될 수 있다 — 출력 뒤 구별 건수를 확인한다(맨 아래 주석).
--
-- 큰 표를 한 번만 읽는 방법 — TABLESAMPLE BERNOULLI(pct) 로 property 를 한 번 훑으며 행을 pct% 만 남긴다(약 31만 → 3만).
--   SYSTEM 표본은 블록 단위라 쓰지 않는다 — 적재가 자치구 단위라 한 블록의 행이 같은 구에 몰려 구 분포가 틀어진다.
--   남은 행에만 판정 · 등기 · 대장 · 코드를 유일 인덱스로 붙인다. 자치구 순위도 같은 표본에서 센다(다시 읽지 않는다).

\if :{?per_district}
\else
  \set per_district 200
\endif
\if :{?pct}
\else
  \set pct 10
\endif

BEGIN READ ONLY;
SET LOCAL statement_timeout = '120s';

COPY (
  WITH s AS (
    SELECT p.property_id, p.district, p.latitude, p.longitude, p.property_type_code_id,
           count(*) OVER (PARTITION BY p.district) AS n_district
      FROM property p TABLESAMPLE BERNOULLI (:pct) REPEATABLE (20261004)
     WHERE p.latitude IS NOT NULL
       AND p.longitude IS NOT NULL
  ),
  tiers AS (
    SELECT district,
           CASE WHEN row_number() OVER (ORDER BY max(n_district) DESC, district) <= 3 THEN 'top' ELSE 'rest' END AS tier
      FROM s
     GROUP BY district
  ),
  ok AS (
    SELECT s.property_id, s.district, s.latitude, s.longitude, pc.code_value AS property_type,
           ra.insurance_eligible_yn AS insurance_eligible,
           row_number() OVER (PARTITION BY s.district ORDER BY md5(s.property_id::text)) AS rn
      FROM s
      JOIN risk_analysis ra ON ra.property_id = s.property_id AND ra.is_latest
      JOIN property_code pc ON pc.code_id = s.property_type_code_id
     WHERE EXISTS (SELECT 1 FROM building_registry br WHERE br.property_id = s.property_id)
       AND EXISTS (SELECT 1 FROM building_ledger bl WHERE bl.property_id = s.property_id)
  )
  SELECT ok.property_id, ok.district, t.tier, ok.latitude, ok.longitude, ok.property_type, ok.insurance_eligible
    FROM ok
    JOIN tiers t ON t.district = ok.district
   WHERE ok.rn <= :per_district
   ORDER BY t.tier DESC, ok.district, ok.rn
) TO STDOUT WITH (FORMAT csv, HEADER true);

COMMIT;

-- 확인(출력 파일로 — DB 를 다시 읽지 않는다):
--   구별 건수     awk -F, 'NR>1{n[$2]++} END{for(d in n) print d, n[d]}' property-ids.csv | sort
--   묶음 · 가입   awk -F, 'NR>1{print $3, $7}' property-ids.csv | sort | uniq -c
-- top 묶음에 insurance_eligible = t 가 하나도 없으면 loans/limit 이 rest 묶음으로만 돈다(플랜이 로그에 남긴다).
