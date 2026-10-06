-- 관심 매물 — 사용자 구간 하나 (#376). 40_users.sql 의 관심 매물 단계를 대신한다.
-- 한 번에 1,000만을 넣으면 문장 끝에 몰린 외래키 확인(매물 행 KEY SHARE)이 매물 테이블을 무작위로 읽어
-- 1시간 넘게 걸렸다(2026-10-03, 크레딧이 남아 있어도 단일 프로세스 무작위 읽기 ≈ 1,450회/초). 그래서
--   ① 사용자 구간마다 커밋하고  ② 매물 식별자 순서로 넣어 외래키 확인이 매물 테이블을 앞에서부터 읽게 한다.
-- 매물 고르기 · monitoring_yn 은 #376 과 같다(상위 3개 구 60%). 1인 평균 5(INF-06 #390 승인 — 지수 분포 1 + ⌊Exp(4.5)⌋,
-- 기댓값 1 + 1/(e^(1/4.5) − 1) = 5.02, 상한 200). 시험 계정(1억 미만)은 43_test_accounts.sql 이 따로 30 ~ 50개.
-- 실행: psql -v from_user=100000001 -v to_user=100050000 -f 41_wishlist.sql   (pick 표는 41_pick.sql 이 만든다)
\set ON_ERROR_STOP 1
BEGIN;
INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition, created_at)
SELECT w.user_id, p.property_id, coalesce(ns.is_active, TRUE), 'RISK_AND_REGISTRY',
       w.created_at + make_interval(secs => (w.r2 % 2000000)::INT)
  FROM (
    SELECT u.user_id, u.created_at, g.n,
           (hashint8(u.user_id * 1000 + g.n) & 2147483647) AS r1,
           (hashint8(u.user_id * 1000 + g.n + 500) & 2147483647) AS r2
      FROM users u
      CROSS JOIN LATERAL generate_series(1, least(200, 1 + floor(-ln(1 - ((hashint8(u.user_id) & 2147483647) % 1000000)
                                                               / 1000000.0) * 4.5)::INT)) AS g(n)
     WHERE u.user_id BETWEEN :from_user AND :to_user
       AND NOT EXISTS (SELECT 1 FROM wishlist x WHERE x.user_id = u.user_id)
  ) w
  JOIN loadtest.zone_size z ON z.zone = CASE WHEN w.r1 % 100 < 60 THEN 'HOT' ELSE 'REST' END
  JOIN loadtest.pick p ON p.zone = z.zone AND p.rn = 1 + (w.r2 % z.n)
  LEFT JOIN notification_subscription ns
         ON ns.user_id = w.user_id AND ns.subscription_type = 'WISHLIST_MONITORING'
 ORDER BY p.property_id
ON CONFLICT ON CONSTRAINT uq_wishlist_user_property DO NOTHING;
COMMIT;
