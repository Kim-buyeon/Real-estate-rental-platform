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

-- 알림 · 관심 매물 알림 넣기 동안만 행 단위 외래키 확인을 끈다(이 트랜잭션 안에서만 — SET LOCAL). 외래키 확인은 행마다 알림 표
-- 행을 찾아 잠금 표시(FOR KEY SHARE)를 쓴다 — 2026-10-03 운영에서 500만 행에 19분 동안 66 MB 밖에 못 넣었다(취소).
-- 알림의 사용자는 관심 매물에서, 관심 매물 알림의 번호는 이 트랜잭션에서 방금 넣은 알림에서 골랐으므로 외래키가 어긋날 수 없다.
SET LOCAL session_replication_role = replica;
INSERT INTO notification (notif_id, user_id, notif_type, is_read, created_at)
SELECT notif_id, user_id, 'RISK_CHANGE', is_read, analyzed_at FROM changes ORDER BY user_id;
INSERT INTO wishlist_notification (notif_id, wish_id, property_id, change_type, before_value, after_value, detected_at)
SELECT notif_id, wish_id, property_id, 'RISK_GRADE', before_value, after_value, analyzed_at FROM changes
 ORDER BY property_id;
SET LOCAL session_replication_role = origin;
-- 끈 외래키의 대신 확인은 커밋 뒤 90_verify.sql ⑦ 이 해시 반조인 한 번으로 한다. 트랜잭션 안에서 EXISTS 로 확인하면
-- 알림 500만 건마다 알림 표를 무작위로 찾아가 끝나지 않는다(2026-10-03 운영 10분 넘게 — 취소).
SELECT count(*) AS notifications FROM changes;
COMMIT;
