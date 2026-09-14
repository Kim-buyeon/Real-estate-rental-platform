-- 알림 생성(NOTI-02) — 알림 이력이 원천 행의 삭제에 막히지도, 함께 사라지지도 않게 한다.
--
-- 1) wishlist_notification.property_id 추가(NOT NULL, property FK). 알림 목록의 propertyId 는 관심 매물이 아니라 이 컬럼에서
--    읽는다 — 관심을 해제해도 이력이 매물을 가리킨다. 기존 행은 관심 매물에서 채운다(지금은 알림을 만드는 경로가 없어 비어 있다).
-- 2) wishlist_notification.wish_id NULL 허용 + ON DELETE SET NULL. CASCADE 로 두면 관심 해제가 알림 이력을 지운다 —
--    「알림 목록 조회가 완전한 확인 수단」(아키텍처 설계서 알림 전달 1.1).
-- 3) property_notification · rate_notification 의 subscription_id NULL 허용 + ON DELETE SET NULL. 구독 설정 수정(NOTI-01)은
--    사용자 행을 지우고 다시 넣으므로, 과거 알림이 가리키는 구독 행이 삭제를 막으면 안 된다.
-- 4) wishlist (property_id) 인덱스. 알림 대상은 매물의 관심 등록자다. 기존 유일 제약 (user_id, property_id) 은 선행 컬럼이
--    user_id 라 매물 기준 조회에 쓰이지 않는다.
--
-- FK 이름은 V1 인라인 REFERENCES 의 PostgreSQL 기본 이름({테이블}_{컬럼}_fkey)이다.

ALTER TABLE wishlist_notification
    ADD COLUMN property_id BIGINT REFERENCES property (property_id);

UPDATE wishlist_notification wn
SET property_id = w.property_id
FROM wishlist w
WHERE w.wish_id = wn.wish_id;

ALTER TABLE wishlist_notification
    ALTER COLUMN property_id SET NOT NULL,
    ALTER COLUMN wish_id DROP NOT NULL,
    DROP CONSTRAINT wishlist_notification_wish_id_fkey,
    ADD CONSTRAINT wishlist_notification_wish_id_fkey
        FOREIGN KEY (wish_id) REFERENCES wishlist (wish_id) ON DELETE SET NULL;

ALTER TABLE property_notification
    ALTER COLUMN subscription_id DROP NOT NULL,
    DROP CONSTRAINT property_notification_subscription_id_fkey,
    ADD CONSTRAINT property_notification_subscription_id_fkey
        FOREIGN KEY (subscription_id) REFERENCES notification_subscription (subscription_id) ON DELETE SET NULL;

ALTER TABLE rate_notification
    ALTER COLUMN subscription_id DROP NOT NULL,
    DROP CONSTRAINT rate_notification_subscription_id_fkey,
    ADD CONSTRAINT rate_notification_subscription_id_fkey
        FOREIGN KEY (subscription_id) REFERENCES notification_subscription (subscription_id) ON DELETE SET NULL;

CREATE INDEX idx_wishlist_property ON wishlist (property_id);
