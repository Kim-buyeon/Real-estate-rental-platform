-- 사용자 축 (#376 · INF-06 #390) — users · user_auth(생성기 출력) → 시험 계정 표 → 알림 구독.
-- 관심 매물은 41_pick · 41_wishlist · 43_test_accounts, 알림은 42_notification 이 따로 한다(한 문장에 몰면 끝의 외래키 확인이 1시간 넘게 걸렸다).
--
-- 사용자 총수 users_total(기본 300,000) — 기준선 사용자(1억 미만, 실사용자 · 가입 API 로 만든 시험 계정) + 생성기 사용자 앞 N명.
--   N = users_total − 기준선 사용자 수. 생성기 사용자는 user_id = 1억 + 순번이라 앞 N명 = user_id ≤ 1억 + N. run.sh copy-users 가
--   파일 앞 N줄만 적재용 표에 넣는다 — 여기서도 같은 경계로 거른다.
-- 시험 계정 — 이메일 test_like(기본 'jmeter+%@rental.test', make-accounts.sh 의 jmeter+NNN@rental.test)인 사용자. 가입 API 로 이미
--   있어야 한다(run.sh SIGNUP=true). loadtest.test_users 에 담아 41 · 42 · 43 이 쓴다. 이 파일 뒤에 가입한 계정은 빠진다 — 다시 돌린다.
-- 알림 구독 — 생성기 사용자 30%, 시험 계정 전부. 1인 5행: 토글 셋(RATE_CHANGE · WISHLIST_MONITORING · CONSULT_SCHEDULE) + 신규 매물 자치구
--   둘(서로 다른 구). 앱 replace() 모양과 같은 열. 시험 계정은 관심 매물 모니터링을 켠다(알림이 쌓이게).
\set ON_ERROR_STOP 1
\if :{?users_total}
\else
  \set users_total 300000
\endif
\if :{?test_like}
\else
  \set test_like 'jmeter+%@rental.test'
\endif

BEGIN;
SELECT :users_total - count(*) AS new_users FROM users WHERE user_id < 100000000 \gset
INSERT INTO users (user_id, name, email, phone, role, annual_income, credit_score, existing_loan,
                   existing_loan_annual_payment, has_house, own_fund, created_at, deleted_at)
SELECT user_id, name, email, phone, role, annual_income, credit_score, existing_loan, existing_loan_annual_payment,
       has_house, own_fund, created_at, deleted_at
  FROM loadtest.stage_users s
 WHERE s.user_id > 100000000 AND s.user_id <= 100000000 + :new_users
   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = s.user_id)
 ORDER BY s.user_id;

INSERT INTO user_auth (user_id, auth_type, provider_id, password_hash, last_login_at, created_at)
SELECT user_id, auth_type, provider_id, password_hash, last_login_at, created_at
  FROM loadtest.stage_user_auth s
 WHERE s.user_id > 100000000 AND s.user_id <= 100000000 + :new_users
   AND NOT EXISTS (SELECT 1 FROM user_auth a WHERE a.auth_type = s.auth_type AND a.provider_id = s.provider_id)
 ORDER BY s.user_id;

DROP TABLE IF EXISTS loadtest.test_users;
CREATE TABLE loadtest.test_users AS
SELECT user_id, created_at FROM users WHERE email LIKE :'test_like' AND deleted_at IS NULL;
ALTER TABLE loadtest.test_users ADD PRIMARY KEY (user_id);
SELECT count(*) AS test_accounts FROM loadtest.test_users;
COMMIT;

-- 알림 구독
BEGIN;
CREATE TEMP TABLE sub_users ON COMMIT DROP AS
SELECT u.user_id, u.created_at, (hashint8(u.user_id) & 2147483647) AS h, FALSE AS is_test
  FROM users u
 WHERE u.user_id >= 100000000 AND (hashint8(u.user_id) & 2147483647) % 100 < 30
   AND NOT EXISTS (SELECT 1 FROM notification_subscription ns WHERE ns.user_id = u.user_id)
UNION ALL
SELECT t.user_id, t.created_at, (hashint8(t.user_id) & 2147483647), TRUE
  FROM loadtest.test_users t
 WHERE NOT EXISTS (SELECT 1 FROM notification_subscription ns WHERE ns.user_id = t.user_id);

INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type, deposit_min,
                                       deposit_max, is_active, created_at)
SELECT user_id, t.type, NULL, NULL, 0, NULL,
       CASE WHEN is_test THEN TRUE
            WHEN t.type = 'WISHLIST_MONITORING' THEN h % 100 < 85      -- 끈 사람 15% — 설계값(#376)
            WHEN t.type = 'RATE_CHANGE' THEN h % 100 < 60 ELSE h % 100 < 40 END,
       created_at + interval '1 day'
  FROM sub_users CROSS JOIN (VALUES ('RATE_CHANGE'), ('WISHLIST_MONITORING'), ('CONSULT_SCHEDULE')) AS t(type)
 ORDER BY user_id;

-- 신규 매물 자치구 둘 — 첫째는 1 ~ 25위 중 하나, 둘째는 첫째를 건너뛴 다른 구(같은 구 두 행이 나오지 않게)
WITH districts AS (
    SELECT district, row_number() OVER (ORDER BY count(*) DESC, district) AS rk FROM property GROUP BY district
), choice AS (
    SELECT su.user_id, su.created_at, su.h,
           1 + (hashint8(su.user_id * 31 + 1) & 2147483647) % 25 AS rk1,
           1 + ((hashint8(su.user_id * 31 + 1) & 2147483647) % 25 + 1 + (hashint8(su.user_id * 31 + 2) & 2147483647) % 24) % 25
               AS rk2
      FROM sub_users su
)
INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type, deposit_min,
                                       deposit_max, is_active, created_at)
SELECT c.user_id, 'NEW_PROPERTY', d.district,
       (ARRAY['DEPOSIT_ONLY', 'MONTHLY_RENT', NULL])[1 + (c.h / 11 % 3)::INT], 0,
       (ARRAY[300000000, 500000000, 800000000, NULL])[1 + (c.h / 13 % 4)::INT]::BIGINT, TRUE,
       c.created_at + interval '1 day'
  FROM choice c
  JOIN districts d ON d.rk IN (c.rk1, c.rk2)
 ORDER BY c.user_id, d.district;

SELECT count(DISTINCT user_id) AS subscribed_users, count(*) AS subscription_rows FROM notification_subscription;
COMMIT;
