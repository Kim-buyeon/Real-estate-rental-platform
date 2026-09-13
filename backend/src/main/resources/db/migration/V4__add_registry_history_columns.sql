-- 등기 이력 조회(RISK-07) — API 명세서(위험도 분석) 1.3 이 요구하는데 V1 스키마에 없는 컬럼을 더한다.
-- 세 테이블은 이 변경 전까지 애플리케이션이 쓰는 경로가 없어 비어 있다. 그래서 NOT NULL 을 기본값 없이 추가한다.

-- 표제부
--   data_source — 응답의 dataSource. 상수로 박으면 실제 연동이 붙는 날 거짓이 되므로 수집한 구현이 기록한다.
--   created_at  — updated_at 이 있는 테이블의 엔티티는 BaseEntity(생성일시 + 수정일시)를 상속한다.
--                 V1 에는 생성일시 컬럼이 없어 상속할 수 없었다. 최초 수집 시각으로 쓴다.
--   property_id UNIQUE — 매물과 표제부는 1:1 이다(데이터베이스 설계서 2장). 두 인스턴스가 같은 매물을
--                 동시에 처음 조회하면 애플리케이션 락으로는 중복 행을 막을 수 없다. 제약이 막는다.
ALTER TABLE building_registry
    ADD COLUMN data_source VARCHAR(20) NOT NULL,
    ADD COLUMN created_at  TIMESTAMP   NOT NULL DEFAULT now();

ALTER TABLE building_registry
    ADD CONSTRAINT uq_building_registry_property_id UNIQUE (property_id);

-- 갑구
--   rank_no            — 응답의 rankNo(순위번호). 을구의 priority_no 와 같은 뜻이다
--   right_type         — 응답의 rightType(등기 목적). 소유권보존 · 이전 · 압류 · 가압류 · 경매개시결정 · 신탁 ·
--                        가등기 · 임차권등기명령. 열거 상수명이 20자를 넘는 것이 있어(PROVISIONAL_REGISTRATION —
--                        명세 1.1 warnings 값과 같은 이름) 30 으로 둔다
--   registration_cause — 응답의 cause(등기원인). 을구의 같은 이름 컬럼과 길이를 맞춘다
ALTER TABLE ownership_history
    ADD COLUMN rank_no            INTEGER     NOT NULL,
    ADD COLUMN right_type         VARCHAR(30) NOT NULL,
    ADD COLUMN registration_cause VARCHAR(50);
