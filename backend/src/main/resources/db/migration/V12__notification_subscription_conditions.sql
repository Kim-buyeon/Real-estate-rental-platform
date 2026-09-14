-- 알림 구독 설정(NOTI-01) — 유형마다 조건이 다르고, 신규 매물은 자치구마다 한 행이다.
--
-- 1) 조건 컬럼 NOT NULL 해제. 금리 변동 · 관심 매물 모니터링 · 상담 일정은 수신 여부만 가져 조건이 없다.
--    신규 매물도 계약 유형 · 보증금 상한은 선택이다(없으면 전체 · 상한 없음). deposit_min 은 명세에 필드가 없어
--    기본값 0 그대로 둔다.
-- 2) contract_type VARCHAR(10) → VARCHAR(20). 저장 값은 ContractType 상수명이고 DEPOSIT_ONLY 가 12자다.
-- 3) 유일 인덱스 (user_id, subscription_type, target_district) NULLS NOT DISTINCT.
--    조건 없는 유형은 target_district 가 NULL 이라 기본 동작(NULL 끼리 다름)으로는 사용자당 한 행을 막지 못한다.
--    선행 컬럼이 user_id 라 사용자별 조회 · 일괄 삭제의 인덱스를 겸한다. 별도 인덱스를 두지 않는다.
ALTER TABLE notification_subscription
    ALTER COLUMN target_district DROP NOT NULL,
    ALTER COLUMN contract_type DROP NOT NULL,
    ALTER COLUMN deposit_max DROP NOT NULL,
    ALTER COLUMN contract_type TYPE VARCHAR(20);

CREATE UNIQUE INDEX uq_notification_subscription_user_type_district
    ON notification_subscription (user_id, subscription_type, target_district) NULLS NOT DISTINCT;
