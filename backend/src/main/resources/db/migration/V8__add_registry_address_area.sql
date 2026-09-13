-- 명의 · 문서 정합 확인(RISK-04) — 등기 표제부의 주소 · 전용면적을 건축물대장과 대조하는데 V1 · V4 스키마에 없다.
--
--   registry_address — 표제부 건물 주소. 대장의 ledger_address 와 같은 길이다
--   exclusive_area   — 표제부 전용면적(㎡). 대장 · 매물의 전용면적과 같은 정밀도
--
-- NULL 을 허용한다. 이 변경 전에 수집된 등기 행이 있으면 NOT NULL 추가가 실패하고, 그 행에 채울 값이 없다.
-- 판정은 NULL 을 불일치로 본다.
ALTER TABLE building_registry
    ADD COLUMN registry_address VARCHAR(200),
    ADD COLUMN exclusive_area   NUMERIC(7, 2);
