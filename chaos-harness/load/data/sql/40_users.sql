-- 사용자 축 (#376) — users · user_auth(생성기 출력) → 알림 구독(30%) → 관심 매물(1인 평균 10) → 알림(이력의 등급 변화).
-- 앞서 stage_users · stage_user_auth 를 \copy 로 채운다(run.sh). 사용자 1억 번대만 다룬다.
\set ON_ERROR_STOP 1
BEGIN;

INSERT INTO users (user_id, name, email, phone, role, annual_income, credit_score, existing_loan,
                   existing_loan_annual_payment, has_house, own_fund, created_at, deleted_at)
SELECT user_id, name, email, phone, role, annual_income, credit_score, existing_loan, existing_loan_annual_payment,
       has_house, own_fund, created_at, deleted_at
  FROM loadtest.stage_users s
 WHERE s.user_id >= 100000000 AND NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = s.user_id);

INSERT INTO user_auth (user_id, auth_type, provider_id, password_hash, last_login_at, created_at)
SELECT user_id, auth_type, provider_id, password_hash, last_login_at, created_at
  FROM loadtest.stage_user_auth s
 WHERE NOT EXISTS (SELECT 1 FROM user_auth a WHERE a.auth_type = s.auth_type AND a.provider_id = s.provider_id);
COMMIT;

-- 알림 구독 — 사용자 30%. 앱 replace() 모양: 토글 셋 + 신규 매물(자치구 1 ~ 3개, 조건 없으면 NULL 비활성 1행)
BEGIN;
CREATE TEMP TABLE sub_users ON COMMIT DROP AS
SELECT user_id, created_at, (hashint8(user_id) & 2147483647) AS h
  FROM users WHERE user_id >= 100000000 AND (hashint8(user_id) & 2147483647) % 100 < 30
   AND NOT EXISTS (SELECT 1 FROM notification_subscription ns WHERE ns.user_id = users.user_id);

INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type, deposit_min,
                                       deposit_max, is_active, created_at)
SELECT user_id, t.type, NULL, NULL, 0, NULL,
       CASE t.type WHEN 'WISHLIST_MONITORING' THEN h % 100 < 85      -- 끈 사람 15% — 설계값
                   WHEN 'RATE_CHANGE' THEN h % 100 < 60 ELSE h % 100 < 40 END,
       created_at + interval '1 day'
  FROM sub_users CROSS JOIN (VALUES ('RATE_CHANGE'), ('WISHLIST_MONITORING'), ('CONSULT_SCHEDULE')) AS t(type);

WITH districts AS (
    SELECT district, row_number() OVER (ORDER BY count(*) DESC) AS rk FROM property GROUP BY district
), choice AS (
    SELECT su.user_id, su.created_at, su.h, g.n
      FROM sub_users su CROSS JOIN LATERAL generate_series(1, 1 + (su.h / 7 % 3)::INT) AS g(n)
     WHERE su.h / 7 % 10 < 7                                         -- 신규 매물 알림을 쓴 사람 70% — 설계값
)
INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type, deposit_min,
                                       deposit_max, is_active, created_at)
SELECT DISTINCT ON (c.user_id, d.district) c.user_id, 'NEW_PROPERTY', d.district,
       (ARRAY['DEPOSIT_ONLY', 'MONTHLY_RENT', NULL])[1 + (c.h / 11 % 3)::INT], 0,
       (ARRAY[300000000, 500000000, 800000000, NULL])[1 + (c.h / 13 % 4)::INT]::BIGINT, TRUE,
       c.created_at + interval '1 day'
  FROM choice c
  JOIN districts d ON d.rk = 1 + ((hashint8(c.user_id * 31 + c.n) & 2147483647) % 25)
 ORDER BY c.user_id, d.district;

INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type, deposit_min,
                                       deposit_max, is_active, created_at)
SELECT su.user_id, 'NEW_PROPERTY', NULL, NULL, 0, NULL, FALSE, su.created_at + interval '1 day'
  FROM sub_users su
 WHERE NOT EXISTS (SELECT 1 FROM notification_subscription ns
                    WHERE ns.user_id = su.user_id AND ns.subscription_type = 'NEW_PROPERTY');
COMMIT;

-- 관심 매물 — 1인 평균 10(지수 분포, 1 ~ 200, 설계값). 상위 3개 구 60% · 나머지 40%(트래픽 정의서 3.3)
BEGIN;
CREATE TEMP TABLE pick ON COMMIT DROP AS
WITH ranked AS (SELECT district, row_number() OVER (ORDER BY count(*) DESC) AS rk FROM property GROUP BY district)
SELECT p.property_id, CASE WHEN r.rk <= 3 THEN 'HOT' ELSE 'REST' END AS zone,
       row_number() OVER (PARTITION BY CASE WHEN r.rk <= 3 THEN 'HOT' ELSE 'REST' END ORDER BY p.property_id) AS rn
  FROM property p JOIN ranked r ON r.district = p.district
  JOIN risk_analysis ra ON ra.property_id = p.property_id AND ra.is_latest;
CREATE INDEX ON pick (zone, rn);
ANALYZE pick;

CREATE TEMP TABLE zone_size ON COMMIT DROP AS SELECT zone, max(rn) AS n FROM pick GROUP BY zone;

INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition, created_at)
SELECT w.user_id, p.property_id,
       coalesce((SELECT ns.is_active FROM notification_subscription ns
                  WHERE ns.user_id = w.user_id AND ns.subscription_type = 'WISHLIST_MONITORING'), TRUE),
       'RISK_AND_REGISTRY', w.created_at + make_interval(secs => (w.r2 % 2000000)::INT)
  FROM (
    SELECT u.user_id, u.created_at, g.n,
           (hashint8(u.user_id * 1000 + g.n) & 2147483647) AS r1,
           (hashint8(u.user_id * 1000 + g.n + 500) & 2147483647) AS r2
      FROM users u
      CROSS JOIN LATERAL generate_series(1, least(200, 1 + floor(-ln(1 - ((hashint8(u.user_id) & 2147483647) % 1000000)
                                                               / 1000000.0) * 9)::INT)) AS g(n)
     WHERE u.user_id >= 100000000
       AND NOT EXISTS (SELECT 1 FROM wishlist x WHERE x.user_id = u.user_id)
  ) w
  JOIN zone_size z ON z.zone = CASE WHEN w.r1 % 100 < 60 THEN 'HOT' ELSE 'REST' END
  JOIN pick p ON p.zone = z.zone AND p.rn = 1 + (w.r2 % z.n)
ON CONFLICT ON CONSTRAINT uq_wishlist_user_property DO NOTHING;
COMMIT;

-- 알림 — 관심 등록 뒤에 일어난 등급 변화만, 그 시각 모니터링 중인 사용자에게(앱 createForWishlist)
BEGIN;
CREATE TEMP TABLE changes ON COMMIT DROP AS
SELECT w.wish_id, w.user_id, w.property_id, ra.previous_grade AS before_value, ra.risk_grade AS after_value,
       ra.analyzed_at, (hashint8(w.wish_id + ra.risk_id) & 2147483647) % 100 < 80 AS is_read,
       nextval(pg_get_serial_sequence('notification', 'notif_id')) AS notif_id
  FROM wishlist w
  JOIN risk_analysis ra ON ra.property_id = w.property_id
 WHERE w.user_id >= 100000000 AND w.monitoring_yn
   AND ra.previous_grade IS NOT NULL AND ra.previous_grade <> ra.risk_grade
   AND ra.analyzed_at > w.created_at;

INSERT INTO notification (notif_id, user_id, notif_type, is_read, created_at)
SELECT notif_id, user_id, 'RISK_CHANGE', is_read, analyzed_at FROM changes;
INSERT INTO wishlist_notification (notif_id, wish_id, property_id, change_type, before_value, after_value, detected_at)
SELECT notif_id, wish_id, property_id, 'RISK_GRADE', before_value, after_value, analyzed_at FROM changes;
SELECT count(*) AS notifications FROM changes;
COMMIT;
