-- 건축물대장 정보 조회(PROP-04) — API 명세서(매물) 1.8 이 요구하는데 V1 스키마에 없는 컬럼을 더한다.
-- 이 변경 전까지 애플리케이션이 building_ledger 에 쓰는 경로가 없어 비어 있다. 그래서 NOT NULL 을 기본값 없이 추가한다.
--
--   total_floor_area — 응답의 totalFloorArea(연면적 ㎡). 건물 전체 바닥면적의 합이라 전용면적(7,2)보다 자릿수가 크다.
--                      V1 의 building_area 는 건축면적(건물이 대지를 덮는 수평투영면적)으로 뜻이 달라 쓰지 않는다
--   exclusive_area   — 응답의 exclusiveArea(전용면적 ㎡). property.area_sqm 과 같은 정밀도
--   approval_date    — 응답의 approvalDate(사용승인일)
--   data_source      — 수집 출처. 상수로 박으면 실제 연동이 붙는 날 거짓이 되므로 수집한 구현이 기록한다
--   created_at       — updated_at 이 있는 테이블의 엔티티는 BaseEntity(생성일시 + 수정일시)를 상속한다.
--                      V1 에는 생성일시 컬럼이 없어 상속할 수 없었다. 최초 수집 시각으로 쓴다
--   property_id UNIQUE — 매물과 대장은 1:1 이다(데이터베이스 설계서 2장). 두 인스턴스가 같은 매물을 동시에 처음
--                      조회하면 애플리케이션 락으로는 중복 행을 막을 수 없다. 제약이 막는다
--
-- 면적 · 사용승인일은 NULL 을 허용한다. 실제 대장에는 사용승인일이 비어 있는 건물(미사용승인)이 있고,
-- Real 연동이 붙기 전에 NOT NULL 로 묶으면 그 건을 저장할 수 없다.
ALTER TABLE building_ledger
    ADD COLUMN total_floor_area NUMERIC(10, 2),
    ADD COLUMN exclusive_area   NUMERIC(7, 2),
    ADD COLUMN approval_date    DATE,
    ADD COLUMN data_source      VARCHAR(20) NOT NULL,
    ADD COLUMN created_at       TIMESTAMP   NOT NULL DEFAULT now();

ALTER TABLE building_ledger
    ADD CONSTRAINT uq_building_ledger_property_id UNIQUE (property_id);
