-- 기준선 매물 대장 채우기 (INF-06 · #390) — 대장이 없는 기준선 매물(운영 31만 중 ≈ 24만)에 MOCK 대장을 넣는다.
--
-- 왜 — 대장 행이 없으면 위험도 · 대출 한도 · 대장 조회가 그 자리에서 건축HUB 를 부른다(LedgerCommandService.collectIfAbsent 는
-- 행이 있는지만 본다). 시험 중 외부 호출 · 일일 한도가 끼면 측정이 흐려진다.
-- 왜 MOCK 인가 — 운영 대장 모드는 real 이고, real 에서 MOCK 대장은 판정 입력이 아니다(RiskAnalysisCommandService 「Mock 대장」).
-- 그래서 이 행을 넣어도 저장된 판정의 결론이 바뀌지 않는다 — 앱이 대장을 저장할 때 세우는 재분석 대기(V18)도 세우지 않는다.
-- 건축HUB 실대장을 같은 건물에서 베껴 오면 판정 입력이 바뀌어(주소 · 면적 · 위반건축물) 기준선 판정이 낡는다 — 하지 않는다.
-- 시험 동안 Mock 대장 교체 배치(RISK_BATCH_MOCKLEDGERREPLACE_ENABLED)는 꺼 둔다 — 켜면 이 행들을 건축HUB 로 교체하러 나간다.
--
-- 지은 값 (MockBuildingLedgerClient 와 같은 범위 · 다른 난수 — 화면 표시용이고 판정에 쓰이지 않는다)
--   주소 = 매물 주소, 소유자 = 임대인명, 주용도 = 아파트 공동주택 · 그 외 업무시설, 구조 철근콘크리트구조, 전용면적 = 매물 면적,
--   연면적 = 전용 × 30 ~ 149, 건축면적 = 연면적의 10 ~ 39 %, 사용승인일 1985-01-01 + 0 ~ 7,299일, 위반건축물 5 %
-- 구간마다 커밋, 매물 식별자 순서로 넣는다 — 외래키 확인(매물 행)이 매물 표를 앞에서부터 읽는다(README 「함정」).
-- 실행: psql -v ON_ERROR_STOP=1 -v lo=1 -v hi=100000 -f 25_ledger_fill.sql   (run.sh ledger-fill 이 구간을 돈다)
\set ON_ERROR_STOP 1
BEGIN;
SET LOCAL lock_timeout = '5s';
WITH ins AS (
INSERT INTO building_ledger (property_id, ledger_address, owner_name, building_purpose, building_structure,
                             building_area, violation_yn, total_floor_area, exclusive_area, approval_date, data_source,
                             created_at, updated_at)
SELECT p.property_id, p.address, p.landlord_name,
       CASE pc.code_value WHEN 'APARTMENT' THEN '공동주택' ELSE '업무시설' END, '철근콘크리트구조',
       least(round(p.area_sqm * (30 + (hashint8(p.property_id * 3 + 1) & 2147483647) % 120)
                   * (10 + (hashint8(p.property_id * 3 + 2) & 2147483647) % 30) / 100, 2), 99999.99),
       (hashint8(p.property_id * 5 + 1) & 2147483647) % 100 < 5,
       round(p.area_sqm * (30 + (hashint8(p.property_id * 3 + 1) & 2147483647) % 120), 2),
       p.area_sqm,
       DATE '1985-01-01' + ((hashint8(p.property_id * 5 + 2) & 2147483647) % 7300)::int,
       'MOCK', now(), now()
  FROM property p
  JOIN property_code pc ON pc.code_id = p.property_type_code_id
 WHERE p.property_id BETWEEN :lo AND :hi
   AND p.property_id <= (SELECT max_property_id FROM loadtest.baseline)
   AND NOT EXISTS (SELECT 1 FROM building_ledger l WHERE l.property_id = p.property_id)
 ORDER BY p.property_id
RETURNING 1)
SELECT count(*) AS mock_ledgers_added FROM ins;
COMMIT;
