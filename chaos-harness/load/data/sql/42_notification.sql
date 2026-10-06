-- 알림 (#376 · INF-06 #390) — 관심 매물의 등급 변화 알림(RISK_CHANGE · RISK_GRADE)과 관심 매물 알림 연결 행을 1:1 로 넣는다.
--
-- 목표 — 생성기 사용자 1인 평균 notif_avg(기본 10), 시험 계정은 모니터링 관심 1개당 평균 test_per_wish(기본 10) — 관심 30 ~ 50개 ×
-- 변화가 있는 매물 비율(≈ 0.67) × 10 ≈ 200 ~ 340건. 읽음 80%(시험 계정 70% — 안 읽은 알림이 남게).
-- 알림 내용 — 그 매물의 실제 등급 변화(판정 표의 previous_grade → risk_grade, 서로 다른 행) 중 하나. 그래서 90_verify ⑦(알림의
--   before/after 가 그 매물 판정의 변화인가)이 그대로 맞는다. 변화가 없는 매물(이력 0건)의 관심에는 알림이 없다.
-- #376 과 다른 점 — 「관심 등록 뒤에 일어난 변화만」을 버렸다. 이력은 시각이 과거(최신 − 30일 단위)라 그 조건이면 관심 1개에 많아야
--   2건이고 평균 10 을 낼 수 없다. 알림 시각은 관심 등록 시각과 지금 사이에서 고른다(목록 정렬 · 커서가 실제처럼 흩어지게).
--   관심 1개당 개수 k = ⌊u × (2m + 1)⌋ (u 는 [0, 1) 해시) — 평균 m. 생성기 사용자의 m 은 구간마다
--   m = notif_avg × 구간 사용자 수 ÷ 구간의 알림 대상 관심 수(모니터링 · 변화 있는 매물)로 정한다.
--
-- 범위 — scope=range: user_id from_user ~ to_user(구간마다 커밋, run.sh notify), scope=test: loadtest.test_users 전부.
-- 다시 돌리면 — 알림이 이미 있는 관심은 건너뛴다(관심 단위로 전부 아니면 없음).
-- 변화 표 — loadtest.trans(이 파일이 없으면 만든다 — 판정 표를 한 번 훑는다). 판정 표가 바뀌면 DROP TABLE loadtest.trans 뒤 다시.
\set ON_ERROR_STOP 1
\if :{?scope}
\else
  \set scope range
\endif
\if :{?notif_avg}
\else
  \set notif_avg 10
\endif
\if :{?test_per_wish}
\else
  \set test_per_wish 10
\endif
\if :{?from_user}
\else
  \set from_user 0
\endif
\if :{?to_user}
\else
  \set to_user 0
\endif

CREATE TABLE IF NOT EXISTS loadtest.trans AS
SELECT property_id, previous_grade, risk_grade,
       row_number() OVER (PARTITION BY property_id ORDER BY risk_id) AS tn,
       count(*)     OVER (PARTITION BY property_id)                  AS tc
  FROM risk_analysis
 WHERE previous_grade IS NOT NULL AND previous_grade <> risk_grade;
CREATE UNIQUE INDEX IF NOT EXISTS trans_property_tn ON loadtest.trans (property_id, tn);

BEGIN;
SET LOCAL work_mem = '32MB';
CREATE TEMP TABLE wish_t ON COMMIT DROP AS
SELECT w.wish_id, w.user_id, w.property_id, w.created_at, t.tc,
       (:'scope' = 'test') AS is_test
  FROM wishlist w
  JOIN loadtest.trans t ON t.property_id = w.property_id AND t.tn = 1
 WHERE w.monitoring_yn
   AND CASE WHEN :'scope' = 'test' THEN w.user_id IN (SELECT user_id FROM loadtest.test_users)
            ELSE w.user_id BETWEEN :from_user AND :to_user END
   AND NOT EXISTS (SELECT 1 FROM wishlist_notification x WHERE x.wish_id = w.wish_id);

-- 관심 1개당 평균 m
SELECT CASE WHEN :'scope' = 'test' THEN :test_per_wish::numeric
            ELSE :notif_avg::numeric
                 * (SELECT count(*) FROM users WHERE user_id BETWEEN :from_user AND :to_user)
                 / greatest((SELECT count(*) FROM wish_t), 1) END AS m \gset

CREATE TEMP TABLE changes ON COMMIT DROP AS
SELECT x.wish_id, x.user_id, x.property_id, t.previous_grade AS before_value, t.risk_grade AS after_value,
       x.created_at + (now() - x.created_at)
                      * ((hashint8(x.wish_id * 97 + x.n) & 2147483647) / 2147483648.0)::float8 AS at,
       (hashint8(x.wish_id * 89 + x.n) & 2147483647) % 100 < CASE WHEN x.is_test THEN 70 ELSE 80 END AS is_read
  FROM (
    SELECT wt.*, g.n
      FROM wish_t wt
      CROSS JOIN LATERAL generate_series(1, floor((hashint8(wt.wish_id) & 2147483647) / 2147483648.0
                                                  * (2 * :m + 1))::INT) AS g(n)
  ) x
  JOIN loadtest.trans t ON t.property_id = x.property_id
                       AND t.tn = 1 + (hashint8(x.wish_id * 13 + x.n) & 2147483647) % x.tc
 ORDER BY x.property_id;
ALTER TABLE changes ADD COLUMN notif_id BIGINT;
UPDATE changes SET notif_id = nextval(pg_get_serial_sequence('notification', 'notif_id'));

-- 넣는 동안만 행 단위 외래키 확인을 끈다(이 트랜잭션 안 — SET LOCAL). 외래키 확인은 행마다 상대 표 행을 찾아 잠금 표시(FOR KEY SHARE)를
-- 쓴다 — 2026-10-03 운영에서 500만 행에 19분 동안 66 MB 밖에 못 넣었다(취소). 끄는 외래키는 넷 — 알림.사용자, 관심 매물 알림.알림 번호 ·
-- 관심 · 매물(V13). 사용자 · 관심 · 매물은 관심 매물 행에서, 알림 번호는 이 트랜잭션에서 방금 넣은 알림에서 골랐으므로 어긋날 수 없다.
-- 커밋 뒤 90_verify.sql ⑦ 이 해시 반조인으로 확인한다.
SET LOCAL session_replication_role = replica;
INSERT INTO notification (notif_id, user_id, notif_type, is_read, created_at)
SELECT notif_id, user_id, 'RISK_CHANGE', is_read, at FROM changes ORDER BY user_id;
INSERT INTO wishlist_notification (notif_id, wish_id, property_id, change_type, before_value, after_value, detected_at)
SELECT notif_id, wish_id, property_id, 'RISK_GRADE', before_value, after_value, at FROM changes ORDER BY property_id;
SET LOCAL session_replication_role = origin;
SELECT :'scope' AS scope, :m AS per_wish, (SELECT count(*) FROM wish_t) AS target_wishes, count(*) AS notifications,
       count(*) FILTER (WHERE NOT is_read) AS unread
  FROM changes;
COMMIT;
