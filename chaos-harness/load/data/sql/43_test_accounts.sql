-- 시험 계정 관심 매물 (INF-06 #390) — loadtest.test_users(40_users.sql) 마다 30 ~ 50개(30 + 해시 % 21).
-- 매물은 41_pick.sql 의 고르기 표에서 사용자 구간 관심 매물과 같은 규칙(상위 3개 구 60%)으로 고른다. 이미 있는 관심은 건너뛴다
-- (ON CONFLICT) — 지난 시험이 남긴 관심이 있으면 그만큼 적게 더한다(목표는 「계정당 30 ~ 50 근처」).
-- monitoring_yn 은 구독 설정(시험 계정은 40 이 모니터링 켬)을 따른다 — 알림(42 scope=test)이 쌓인다.
\set ON_ERROR_STOP 1
BEGIN;
INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition, created_at)
SELECT w.user_id, p.property_id, coalesce(ns.is_active, TRUE), 'RISK_AND_REGISTRY',
       least(now() - interval '1 day', w.created_at + make_interval(secs => (w.r2 % 2000000)::INT))
  FROM (
    SELECT t.user_id, t.created_at, g.n,
           (hashint8(t.user_id * 1000 + g.n) & 2147483647) AS r1,
           (hashint8(t.user_id * 1000 + g.n + 500) & 2147483647) AS r2
      FROM loadtest.test_users t
      CROSS JOIN LATERAL generate_series(1, 30 + ((hashint8(t.user_id) & 2147483647) % 21)::INT) AS g(n)
  ) w
  JOIN loadtest.zone_size z ON z.zone = CASE WHEN w.r1 % 100 < 60 THEN 'HOT' ELSE 'REST' END
  JOIN loadtest.pick p ON p.zone = z.zone AND p.rn = 1 + (w.r2 % z.n)
  LEFT JOIN notification_subscription ns
         ON ns.user_id = w.user_id AND ns.subscription_type = 'WISHLIST_MONITORING'
 ORDER BY p.property_id
ON CONFLICT ON CONSTRAINT uq_wishlist_user_property DO NOTHING;
SELECT count(*) AS wishes, count(DISTINCT w.user_id) AS accounts, min(c), max(c)
  FROM (SELECT user_id, count(*) AS c FROM wishlist WHERE user_id IN (SELECT user_id FROM loadtest.test_users) GROUP BY 1) x
  JOIN wishlist w ON w.user_id = x.user_id;
COMMIT;
