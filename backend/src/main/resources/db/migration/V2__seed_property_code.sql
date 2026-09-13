-- PROPERTY_CODE 시드 — 계약 유형 · 매물 유형 · 매물 상태
-- database.md §3-1 (PROPERTY_CODE) · §4.3 (자연 식별자 code_group + code_value)
--
-- property 의 세 FK(contract_type_code_id · property_type_code_id · status_code_id)가 모두 NOT NULL 이라
-- 이 시드가 없으면 매물을 한 건도 적재할 수 없다.
--
-- 재실행 안전성은 Flyway 버전이 보장한다. uq_property_code_group_value 가 있으므로
-- 같은 (group, value) 를 두 번 넣으려 하면 즉시 실패한다 — 조용한 중복은 생기지 않는다.
--
-- 값을 고른 기준: "적재 경로가 실제로 만들어 내는 값만 넣는다."
-- 화면 필터에 쓰일 법한 값이라도 이 값을 세팅하는 코드가 없으면 넣지 않는다.

-- 계약 유형 — conventions.md 「열거값은 대문자 스네이크」의 예시이자 backend/CLAUDE.md ContractType,
-- API 명세서(매물) 1.1 contractType 파라미터와 같은 값이다. 세 값 모두 실거래가 자료에서 구분된다
-- (보증금 · 월세 조합으로 판별한다 — ContractTypeClassifier).
INSERT INTO property_code (code_group, code_value, code_name, code_description, sort_order) VALUES
    ('CONTRACT_TYPE', 'DEPOSIT_ONLY',  '전세',   '월세 없이 보증금만 있는 계약', 1),
    ('CONTRACT_TYPE', 'MONTHLY_RENT',  '월세',   '보증금과 월세가 함께 있는 계약', 2),
    ('CONTRACT_TYPE', 'SEMI_DEPOSIT',  '반전세', '월세가 있으나 보증금이 월세의 240개월치를 넘는 계약 — 한국부동산원 준전세 구분', 3);

-- 매물 유형 — 적재 경로가 호출하는 실거래가 서비스와 1:1 로 대응한다.
-- 국토교통부 전월세 실거래가는 주택 유형마다 서비스가 분리되어 있고 활용 신청도 각각이다.
-- 지금 붙은 서비스는 아파트 전월세 · 오피스텔 전월세 둘뿐이므로 두 값만 넣는다.
-- 연립다세대 · 단독다가구는 API 명세서(매물) 1.1 · 1.6 에 이름이 있으나 적재 경로가 없어 넣지 않는다.
-- 해당 서비스를 붙이는 커밋에서 같이 넣는다 — 값만 먼저 넣으면 언제나 0건인 필터가 생긴다.
INSERT INTO property_code (code_group, code_value, code_name, code_description, sort_order) VALUES
    ('PROPERTY_TYPE', 'APARTMENT', '아파트',   '국토교통부 아파트 전월세 실거래가에서 적재', 1),
    ('PROPERTY_TYPE', 'OFFICETEL', '오피스텔', '국토교통부 오피스텔 전월세 실거래가에서 적재', 2);

-- 매물 상태 — 실거래가 자료에는 매물의 거래 가능 여부가 없다. 적재된 매물은 모두 탐색 대상이므로
-- 적재 경로가 세팅하는 값은 AVAILABLE 하나다. 계약 완료 · 노출 중지 같은 값은 그 상태로 바꾸는
-- 기능이 생길 때 함께 넣는다 (database.md §3-1 의 code_value 예시도 AVAILABLE 을 든다).
INSERT INTO property_code (code_group, code_value, code_name, code_description, sort_order) VALUES
    ('PROPERTY_STATUS', 'AVAILABLE', '거래가능', '탐색 · 판정 대상 매물. 초기 적재가 세팅하는 유일한 상태', 1);
