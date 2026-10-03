-- 알림 (#376) — 40_users.sql 의 알림 단계를 따로 뗀 것. 관심 등록 뒤에 일어난 등급 변화만,
-- 그 시각 모니터링 중인 사용자에게(앱 createForWishlist — RISK_CHANGE · RISK_GRADE · 등급명).
-- 외래키 확인이 매물 · 관심 매물을 앞에서부터 읽도록 매물 식별자 순서로 넣는다(41_wishlist.sql 과 같은 이유).
\set ON_ERROR_STOP 1
BEGIN;
CREATE TEMP TABLE changes ON COMMIT DROP AS
SELECT w.wish_id, w.user_id, w.property_id, ra.previous_grade AS before_value, ra.risk_grade AS after_value,
       ra.analyzed_at, (hashint8(w.wish_id + ra.risk_id) & 2147483647) % 100 < 80 AS is_read
  FROM wishlist w
  JOIN risk_analysis ra ON ra.property_id = w.property_id
 WHERE w.user_id >= 100000000 AND w.monitoring_yn
   AND ra.previous_grade IS NOT NULL AND ra.previous_grade <> ra.risk_grade
   AND ra.analyzed_at > w.created_at
   AND NOT EXISTS (SELECT 1 FROM wishlist_notification x WHERE x.wish_id = w.wish_id)
 ORDER BY w.property_id;
ALTER TABLE changes ADD COLUMN notif_id BIGINT;
UPDATE changes SET notif_id = nextval(pg_get_serial_sequence('notification', 'notif_id'));

INSERT INTO notification (notif_id, user_id, notif_type, is_read, created_at)
SELECT notif_id, user_id, 'RISK_CHANGE', is_read, analyzed_at FROM changes ORDER BY user_id;
INSERT INTO wishlist_notification (notif_id, wish_id, property_id, change_type, before_value, after_value, detected_at)
SELECT notif_id, wish_id, property_id, 'RISK_GRADE', before_value, after_value, analyzed_at FROM changes
 ORDER BY property_id;
SELECT count(*) AS notifications FROM changes;
COMMIT;
