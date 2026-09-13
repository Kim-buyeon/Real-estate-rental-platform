-- 판정 기준 시드 — 보증보험 3사 기준 · 보증료율 · 상품 · 위험 등급 기준
-- database.md §3-10 ~ 15 · 32, business-logic.md 2 · 3 · 4장
--
-- 확인일 2026-09-13. 기관 공식 페이지(HUG EUC-KR, HF · SGI JS 렌더링)는 도구로 본문을 읽지 못해
-- 정부 · 금융위 보도자료, 국회입법조사처, 언론, 서로 독립인 2차 정리 글을 교차해 확인한 값만 넣는다.
-- 확인하지 못한 값은 넣지 않고 아래 주석에 「미확정」으로 남긴다. 값의 갱신 경로는 ADMIN-01 이다.
--
-- 시드는 변경 이력(criteria_change_history)을 만들지 않는다. 최초 변경 행의 before_value 가 시드값을 담는다.

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. GUARANTEE_CRITERIA
--   collateral_ratio 90 — 3사 동일. 2023.2.2 정부 「전세사기 예방 및 피해 지원방안」으로 100 → 90,
--     HUG 2023.5.1(신규) · 2024.1.1(갱신) 시행, HF · SGI 동일 통일. 2024.12.9 HUG 「추가 하향 검토 안 함」.
--   max_deposit — 서비스 대상이 서울(수도권)이므로 수도권 한도. HUG · HF 7억(그 외 5억).
--     SGI 는 아파트 제한 없음 · 그 외 10억 — 아파트 무제한은 sgi_criteria.apartment_unlimited_yn 이 덮는다.
--   senior_debt_ratio_limit — HUG 60(단독 · 다중 · 다가구 80, 2차 출처 2개),
--     HF · SGI 미확정 → NULL, 검사하지 않는다. HF 는 「기타 주택 60 · 단독 · 다가구 80」이라는 조사가 있으나
--     공식 페이지 본문(JS 렌더링)을 직접 읽지 못했고, SGI 는 출처가 상충한다. 정해질 때: 공식 페이지 브라우저 확인.
--   strict_singlehouse_yn — 단독 · 다가구 완화 한도(80)를 담을 컬럼이 미확정이다. 현재 적재 매물은
--     아파트 · 오피스텔뿐이라 판정에 쓰이지 않는다. 기본값 FALSE 로 둔다.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO guarantee_criteria (provider, max_deposit, collateral_ratio, senior_debt_ratio_limit)
VALUES ('HUG', 700000000, 90.00, 60.00),
       ('HF', 700000000, 90.00, NULL),
       ('SGI', 1000000000, 90.00, NULL);

-- 11. HUG_CRITERIA — 할인 대상은 보증료 할인이며 판정에 쓰이지 않는다(개인 자격 — business-logic.md 2장).
INSERT INTO hug_criteria (guarantee_id, metro_deposit_limit, ltv_premium_tiered_yn,
                          newlywed_discount_yn, multichild_discount_yn, social_discount_yn)
SELECT guarantee_id, 700000000, TRUE, TRUE, TRUE, TRUE
FROM guarantee_criteria WHERE provider = 'HUG';

-- 12. HF_CRITERIA
--   loan_linked_required_yn — 전세자금보증 이용자만 가입(HF 고유 조건).
--   lowest_premium_yn — FALSE. 2025.3 부터 LTV 80~90% 구간 0.18% 로 HUG 아파트 저구간보다 비쌀 수 있어 늘 최저가 아니다.
--   youth · newlywed — 「우대가구 0.02%p 인하」만 확인, 개별 여부 미확정 → FALSE.
INSERT INTO hf_criteria (guarantee_id, loan_linked_required_yn, lowest_premium_yn,
                         youth_discount_yn, newlywed_discount_yn)
SELECT guarantee_id, TRUE, FALSE, FALSE, FALSE
FROM guarantee_criteria WHERE provider = 'HF';

-- 13. SGI_CRITERIA — 아파트 제한 없음 · 그 외 10억, 공인중개사 계약 필수(3개 이상 출처 교차 확인).
INSERT INTO sgi_criteria (guarantee_id, apartment_unlimited_yn, other_house_limit, private_insurer_yn,
                          high_value_available_yn, broker_contract_required_yn)
SELECT guarantee_id, TRUE, 1000000000, TRUE, TRUE, TRUE
FROM guarantee_criteria WHERE provider = 'SGI';

-- ─────────────────────────────────────────────────────────────────────────────
-- 14. GUARANTEE_PREMIUM_RATE — 연 %. house_type 은 APARTMENT · OTHER(오피스텔 등 비아파트).
--   HUG — 주택유형 2 × 보증금 4구간 × 전세가율 3구간 = 24칸(0.097~0.211%, 2025.3.31~). 전체 표는 미확정이라
--     넣지 않는다. 행이 없으면 예상 보증료를 null 로 낸다 — 채우지 않는다. 정해질 때: HUG 보증료 탭을 브라우저로 확인.
--   HF — LTV 70 이하 0.04 · 70~80 0.11 · 80~90 0.18, 주택유형 · 보증금 축 없음(2025.3.1 신규분~, HF 공지 2025.1.17).
--   SGI — 기본율 아파트 0.229 · 그 외 0.260(2025.4~, 뉴스1 2025.3.26) × LTV 할인(50 이하 30% · 50~60 20%).
--     구간값 = 기본율 × (1 − 할인율), 소수 셋째 자리 반올림. 80% 초과 할증은 미확정이라 60~90 구간에 기본율.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO guarantee_premium_rate (guarantee_id, house_type, deposit_min, deposit_max,
                                    debt_ratio_min, debt_ratio_max, premium_rate)
SELECT g.guarantee_id, r.house_type, 0, NULL, r.debt_min, r.debt_max, r.rate
FROM guarantee_criteria g
JOIN (VALUES
        ('HF', 'APARTMENT', 0.00, 70.00, 0.040),
        ('HF', 'APARTMENT', 70.00, 80.00, 0.110),
        ('HF', 'APARTMENT', 80.00, 90.00, 0.180),
        ('HF', 'OTHER', 0.00, 70.00, 0.040),
        ('HF', 'OTHER', 70.00, 80.00, 0.110),
        ('HF', 'OTHER', 80.00, 90.00, 0.180),
        ('SGI', 'APARTMENT', 0.00, 50.00, 0.160),
        ('SGI', 'APARTMENT', 50.00, 60.00, 0.183),
        ('SGI', 'APARTMENT', 60.00, 90.00, 0.229),
        ('SGI', 'OTHER', 0.00, 50.00, 0.182),
        ('SGI', 'OTHER', 50.00, 60.00, 0.208),
        ('SGI', 'OTHER', 60.00, 90.00, 0.260)
     ) AS r (provider, house_type, debt_min, debt_max, rate)
  ON r.provider = g.provider;

-- 15. INSURANCE_PRODUCT
INSERT INTO insurance_product (guarantee_id, product_name)
SELECT g.guarantee_id, p.product_name
FROM guarantee_criteria g
JOIN (VALUES
        ('HUG', '전세보증금반환보증'),
        ('HF', '전세지킴보증'),
        ('SGI', '전세금보장신용보험')
     ) AS p (provider, product_name)
  ON p.provider = g.provider;

-- 32. RISK_CRITERIA — 깡통전세 선 80(국토부 · 한국부동산원 임대차시장 사이렌, HUG 안심전세앱 2026.9 위험 예시),
--   SAFE/CAUTION 경계 70(HUG 보증료율 저위험 경계 2025.3.31~). business-logic.md 3 · 4장.
INSERT INTO risk_criteria (negative_equity_ratio, caution_lease_ratio) VALUES (80.00, 70.00);
