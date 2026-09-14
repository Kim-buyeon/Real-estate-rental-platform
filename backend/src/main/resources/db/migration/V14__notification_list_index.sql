-- 알림 목록 · 읽음(NOTI-05) 인덱스.
--
-- 1) notification (user_id, notif_id DESC) — 사용자별 목록의 커서 조회. notif_id 내림차순 정렬을 인덱스 순서로 읽는다.
-- 2) notification (user_id) WHERE is_read = FALSE — 목록마다 함께 내는 읽지 않은 수와 전체 읽음 처리. 읽은 알림은 쌓이기만
--    하므로 읽지 않은 행만 담는 부분 인덱스로 둔다.
-- 3) wishlist_notification (notif_id) — 목록 한 페이지에 상세 행을 붙이는 조인. V1 의 FK 는 인덱스를 만들지 않는다.

CREATE INDEX idx_notification_user_notif ON notification (user_id, notif_id DESC);

CREATE INDEX idx_notification_user_unread ON notification (user_id) WHERE is_read = FALSE;

CREATE INDEX idx_wishlist_notification_notif ON wishlist_notification (notif_id);
