-- 반영 검증 (#376). 위반 건수가 전부 0 이어야 한다. 0 이 아니면 그 행의 이름과 건수가 나온다.
\set ON_ERROR_STOP 1
WITH checks(name, violations) AS (
    VALUES
    ('최신 판정이 매물당 1건이 아님',
     (SELECT count(*) FROM (SELECT o.property_id FROM loadtest.property_origin o
                              LEFT JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
                             WHERE o.bundled GROUP BY o.property_id HAVING count(ra.risk_id) <> 1) x)),
    ('대장이 매물당 2건 이상',
     (SELECT count(*) FROM (SELECT property_id FROM building_ledger GROUP BY property_id HAVING count(*) > 1) x)),
    ('등기가 매물당 2건 이상',
     (SELECT count(*) FROM (SELECT property_id FROM building_registry GROUP BY property_id HAVING count(*) > 1) x)),
    ('조회 키 넷이 일부만 있음',
     (SELECT count(*) FROM property WHERE num_nulls(sigungu_code, bjdong_code, bun, ji) NOT IN (0, 4))),
    ('전세가율이 보증금 · 선순위채권 · 시세와 맞지 않음(새 매물 최신 판정)',
     (SELECT count(*) FROM loadtest.property_origin o
        JOIN property p ON p.property_id = o.property_id
        JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
        LEFT JOIN (SELECT h.registry_id, sum(h.max_bond_amount + coalesce(h.prior_tenant_deposit, 0)) AS s
                     FROM mortgage_history h
                    WHERE h.senior_debt_yn AND h.is_active
                      AND h.registry_id > (SELECT max_registry_id FROM loadtest.baseline)
                    GROUP BY h.registry_id) d ON d.registry_id = ra.registry_id   -- 한 번 묶어 맞댄다(행마다 하위 조회 금지)
       WHERE ra.lease_ratio <> least(round((coalesce(d.s, 0) + p.deposit) * 100.0 / p.market_price, 2), 999.99))),
    ('등급 · 사유 짝이 맞지 않음',
     (SELECT count(*) FROM risk_analysis
       WHERE NOT ((risk_grade = 'SAFE' AND risk_reason = 'INSURANCE_ELIGIBLE')
               OR (risk_grade = 'CAUTION' AND risk_reason = 'LEASE_RATIO_CAUTION')
               OR (risk_grade = 'DANGER' AND risk_reason IN ('NEGATIVE_EQUITY', 'INSURANCE_INELIGIBLE'))))),
    ('보증 3사 합이 insurance_eligible 과 다름',
     (SELECT count(*) FROM risk_analysis
       WHERE insurance_eligible_yn <> (hug_eligible_yn OR hf_eligible_yn OR sgi_eligible_yn))),
    ('최신 판정의 previous_grade 가 바로 앞 이력 등급과 다름(새 매물)',
     (SELECT count(*) FROM loadtest.property_origin o
        JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
        JOIN (SELECT property_id, risk_grade,
                     row_number() OVER (PARTITION BY property_id ORDER BY analyzed_at DESC) AS rn
                FROM risk_analysis
               WHERE NOT is_latest AND risk_id > (SELECT max_risk_id FROM loadtest.baseline)) prev
          ON prev.property_id = o.property_id AND prev.rn = 1   -- 이력 색인이 없어 행마다 찾으면 비용 6.7e11(2026-10-03 EXPLAIN)
       WHERE ra.previous_grade IS DISTINCT FROM prev.risk_grade)),
    ('알림 before/after 가 이력의 변화가 아님',
     (SELECT count(*) FROM wishlist_notification wn
        JOIN notification n ON n.notif_id = wn.notif_id AND n.user_id >= 100000000
       WHERE NOT EXISTS (SELECT 1 FROM risk_analysis ra WHERE ra.property_id = wn.property_id
                            AND ra.previous_grade = wn.before_value AND ra.risk_grade = wn.after_value))),
    ('관심 매물 monitoring_yn 이 구독 설정과 다름',
     (SELECT count(*) FROM wishlist w
        LEFT JOIN notification_subscription ns ON ns.user_id = w.user_id
                                              AND ns.subscription_type = 'WISHLIST_MONITORING'
       WHERE w.user_id >= 100000000 AND w.monitoring_yn <> coalesce(ns.is_active, TRUE))),
    ('주택 보유자인데 소득 0',
     (SELECT count(*) FROM users WHERE user_id >= 100000000 AND has_house AND annual_income <= 0)),
    ('가짜 매물이 실매물이 없는 건물에 있음',
     (SELECT count(*) FROM loadtest.property_origin o JOIN property p ON p.property_id = o.property_id
       WHERE o.kind = 'FAKE' AND p.sigungu_code IS NOT NULL
         AND NOT EXISTS (SELECT 1 FROM loadtest.property_origin r JOIN property q ON q.property_id = r.property_id
                          WHERE r.kind = 'REAL' AND q.sigungu_code = p.sigungu_code AND q.bjdong_code = p.bjdong_code
                            AND q.bun = p.bun AND q.ji = p.ji)
         AND NOT EXISTS (SELECT 1 FROM property q WHERE q.property_id <= (SELECT max_property_id FROM loadtest.baseline)
                            AND q.sigungu_code = p.sigungu_code AND q.bjdong_code = p.bjdong_code
                            AND q.bun = p.bun AND q.ji = p.ji)))
)
SELECT name, violations FROM checks ORDER BY violations DESC, name;

-- 분포 — 판단용(위반 아님)
SELECT 'property' AS what, (SELECT count(*) FROM property) AS total,
       (SELECT count(*) FROM loadtest.property_origin WHERE kind = 'REAL') AS real_new,
       (SELECT count(*) FROM loadtest.property_origin WHERE kind = 'FAKE') AS fake_new;
SELECT o.kind, ra.risk_grade, count(*), round(avg(ra.lease_ratio), 1) AS avg_lease_ratio
  FROM loadtest.property_origin o JOIN risk_analysis ra ON ra.property_id = o.property_id AND ra.is_latest
 GROUP BY 1, 2 ORDER BY 1, 2;
SELECT district, count(*) FILTER (WHERE kind = 'REAL') AS real_new, count(*) FILTER (WHERE kind = 'FAKE') AS fake_new
  FROM loadtest.property_origin GROUP BY district ORDER BY real_new DESC;
SELECT (SELECT count(*) FROM users WHERE user_id >= 100000000) AS users,
       (SELECT count(*) FROM wishlist WHERE user_id >= 100000000) AS wishlist,
       (SELECT count(*) FROM notification_subscription WHERE user_id >= 100000000) AS subscriptions,
       (SELECT count(*) FROM notification WHERE user_id >= 100000000) AS notifications,
       (SELECT count(*) FROM building_ledger l JOIN loadtest.property_origin o USING (property_id)
         WHERE l.data_source = 'BUILDING_HUB') AS new_hub_ledgers,
       (SELECT count(*) FROM building_ledger l JOIN loadtest.property_origin o USING (property_id)
         WHERE l.data_source = 'MOCK') AS new_mock_ledgers;
