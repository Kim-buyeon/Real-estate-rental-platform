-- 반영 검증 (#376). 위반 건수가 전부 0 이어야 한다.
--
-- 큰 테이블은 한 번씩만 읽는다. 첫 판(검사 12개를 한 질의에)은 검사마다 테이블을 처음부터 다시 읽었고,
-- 건물 키(sigungu_code · bjdong_code · bun · ji)에 인덱스가 없는데 매물마다 그 키로 매물 표를 다시 뒤지는 검사가
-- 있었다 — 2026-10-03 운영에서 26분 동안 끝나지 않고 DB-01 디스크 크레딧을 99 → 66% 썼다(중단).
--   ① 제약이 보장하는 것은 데이터를 읽지 않고 카탈로그에서 제약이 살아 있는지만 본다
--   ② 같은 테이블을 보는 검사는 한 번 읽으며 FILTER 로 함께 센다
--   ③ 전세가율 · 보증 3사 · 등급의 값 대조는 51_guarantee_check.sql 이 앱 산식으로 한다 — 여기서 하지 않는다
-- 단계마다 \echo 와 \timing 으로 어디까지 왔는지 보인다.
\set ON_ERROR_STOP 1
\timing on
SET work_mem = '32MB';   -- 건물 키 묶기(해시 집계)가 디스크로 넘치지 않게 — 컨테이너 상한 256m 안

\echo '① 제약 — 매물당 대장 · 등기 1건 이하, 최신 판정 1건 이하, 관심 중복 없음 (데이터를 읽지 않는다)'
SELECT i.indexrelid::regclass AS constraint_index,
       CASE WHEN i.indisunique AND i.indisvalid AND i.indisready THEN 0 ELSE 1 END AS violations
  FROM pg_index i
 WHERE i.indexrelid IN ('uq_building_ledger_property_id'::regclass, 'uq_building_registry_property_id'::regclass,
                        'uq_risk_analysis_latest'::regclass, 'uq_wishlist_user_property'::regclass);
SELECT conrelid::regclass AS "table", conname AS foreign_key, CASE WHEN convalidated THEN 0 ELSE 1 END AS violations
  FROM pg_constraint
 WHERE contype = 'f'
   AND conrelid IN ('risk_analysis'::regclass, 'building_ledger'::regclass, 'building_registry'::regclass,
                    'wishlist'::regclass)
 ORDER BY 1, 2;

\echo '② 판정 표 한 번 — 등급 · 사유 짝, 보증 3사 합, 새 매물의 최신 판정 수'
SELECT count(*) FILTER (WHERE NOT ((risk_grade = 'SAFE' AND risk_reason = 'INSURANCE_ELIGIBLE')
                                OR (risk_grade = 'CAUTION' AND risk_reason = 'LEASE_RATIO_CAUTION')
                                OR (risk_grade = 'DANGER' AND risk_reason IN ('NEGATIVE_EQUITY', 'INSURANCE_INELIGIBLE'))))
           AS grade_reason_mismatch,
       count(*) FILTER (WHERE insurance_eligible_yn IS DISTINCT FROM (hug_eligible_yn OR hf_eligible_yn OR sgi_eligible_yn))
           AS insurance_sum_mismatch,
       count(*) FILTER (WHERE is_latest AND property_id > (SELECT max_property_id FROM loadtest.baseline))
           AS new_latest_rows,
       count(*) FILTER (WHERE NOT is_latest AND property_id > (SELECT max_property_id FROM loadtest.baseline))
           AS new_history_rows
  FROM risk_analysis;

\echo '③ 출처 표 한 번 — 묶음을 만든 새 매물 수(②의 new_latest_rows 와 같아야 한다 — 최신 판정은 제약상 1건 이하)'
SELECT count(*) FILTER (WHERE bundled) AS bundled, count(*) FILTER (WHERE NOT bundled) AS not_bundled,
       count(*) FILTER (WHERE kind = 'REAL') AS real_new, count(*) FILTER (WHERE kind = 'FAKE') AS fake_new
  FROM loadtest.property_origin;

\echo '④ 매물 표 한 번 — 조회 키 넷이 일부만 있음, 가짜 매물만 있는 건물(실매물 · 기준선 매물이 없는 건물)'
-- 건물 키로 묶는다(해시 집계 한 번) — 매물마다 그 키로 매물 표를 다시 뒤지지 않는다. 키가 일부만 있는 행도 같은 읽기에서 센다
WITH b AS (
    SELECT p.sigungu_code, p.bjdong_code, p.bun, p.ji,
           count(*) FILTER (WHERE num_nulls(p.sigungu_code, p.bjdong_code, p.bun, p.ji) NOT IN (0, 4)) AS partial_rows,
           bool_or(o.kind IS DISTINCT FROM 'FAKE') AS has_real_or_baseline,   -- 출처 표에 없으면 기준선 매물이다
           count(*) FILTER (WHERE o.kind = 'FAKE') AS fake_rows
      FROM property p
      LEFT JOIN loadtest.property_origin o ON o.property_id = p.property_id
     GROUP BY 1, 2, 3, 4
)
SELECT sum(partial_rows) AS partial_lookup_key,
       coalesce(sum(fake_rows) FILTER (WHERE sigungu_code IS NOT NULL AND NOT has_real_or_baseline), 0)
           AS fake_without_real_building
  FROM b;

\echo '⑤ 최신 판정의 previous_grade 가 바로 앞 이력 등급과 같은가 — 표본 1%(property_id % 100 = 0, 새 매물)'
-- 전수면 판정 표 950만 행을 매물 · 시각으로 정렬해야 한다(이력 색인 없음). 이력 사슬은 생성기 규칙 하나가 만든 것이라
-- 표본으로 본다 — 표본 크기를 함께 낸다.
WITH s AS (
    SELECT property_id, is_latest, previous_grade, risk_grade, analyzed_at
      FROM risk_analysis
     WHERE property_id > (SELECT max_property_id FROM loadtest.baseline) AND property_id % 100 = 0
), ordered AS (
    SELECT *, lead(risk_grade) OVER (PARTITION BY property_id ORDER BY analyzed_at DESC) AS before_grade
      FROM s
)
SELECT count(*) FILTER (WHERE is_latest) AS sampled_properties,
       count(*) FILTER (WHERE is_latest AND previous_grade IS DISTINCT FROM before_grade) AS violations
  FROM ordered;

\echo '⑥ 관심 매물 표 한 번 — monitoring_yn 이 구독 설정과 다름, 사용자 표 한 번 — 주택 보유자인데 소득 0'
SELECT count(*) FILTER (WHERE w.monitoring_yn <> coalesce(ns.is_active, TRUE)) AS monitoring_mismatch,
       count(*) AS new_wishlist
  FROM wishlist w
  LEFT JOIN notification_subscription ns ON ns.user_id = w.user_id AND ns.subscription_type = 'WISHLIST_MONITORING'
 WHERE w.user_id >= 100000000;
SELECT count(*) FILTER (WHERE has_house AND annual_income <= 0) AS house_without_income, count(*) AS new_users
  FROM users WHERE user_id >= 100000000;

\echo '⑦ 알림 before/after 가 그 매물 판정의 변화인가 — 알림이 있을 때만 의미(notify 뒤). 판정 표를 한 번 해시로 맞댄다'
-- 알림 생성(42)이 넣는 동안 행 단위 외래키 확인을 껐다 — 그 대신 여기서 해시 반조인 한 번씩으로 본다
SELECT (SELECT count(*) FROM wishlist_notification wn LEFT JOIN notification n ON n.notif_id = wn.notif_id
         WHERE n.notif_id IS NULL) AS fk_notif_missing,
       (SELECT count(*) FROM wishlist_notification wn LEFT JOIN wishlist w ON w.wish_id = wn.wish_id
         WHERE wn.wish_id IS NOT NULL AND w.wish_id IS NULL) AS fk_wish_missing,  -- 해제한 관심은 NULL(ON DELETE SET NULL),
       (SELECT count(*) FROM notification n LEFT JOIN users u ON u.user_id = n.user_id
         WHERE u.user_id IS NULL) AS fk_user_missing;
WITH wn AS (
    SELECT wn.property_id, wn.before_value, wn.after_value
      FROM wishlist_notification wn JOIN notification n ON n.notif_id = wn.notif_id
     WHERE n.user_id >= 100000000
), changes AS (
    SELECT DISTINCT ra.property_id, ra.previous_grade, ra.risk_grade
      FROM risk_analysis ra
     WHERE ra.previous_grade IS NOT NULL AND ra.property_id IN (SELECT property_id FROM wn)
)
SELECT (SELECT count(*) FROM wn) AS notifications,
       (SELECT count(*) FROM wn LEFT JOIN changes c ON c.property_id = wn.property_id
                                      AND c.previous_grade = wn.before_value AND c.risk_grade = wn.after_value
         WHERE c.property_id IS NULL) AS violations;
