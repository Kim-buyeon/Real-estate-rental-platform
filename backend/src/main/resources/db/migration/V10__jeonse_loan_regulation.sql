-- LOAN-01 전세자금대출 한도 — 비즈니스 로직 정의서 6장, 데이터베이스 설계서 17 · 18절.
--
-- LTV 는 주택담보대출 규제로 임차인 전세자금대출에 적용되지 않는다(금융위원회 「주택시장 안정화를 위한 대출수요
-- 관리 방안」 2025-10-15, FAQ 2025-10-17). LTV 한도 · Stress DSR 한도(%) 컬럼을 내리고 전세자금대출 한도 기준을 더한다.
-- 두 테이블은 V1 이후 적재된 행이 없으므로 NOT NULL 컬럼을 기본값 없이 더한다.

ALTER TABLE loan_regulation DROP COLUMN ltv_limit;
ALTER TABLE loan_regulation DROP COLUMN stress_dsr_limit;

ALTER TABLE loan_regulation ADD COLUMN deposit_ratio_limit     NUMERIC(5, 2) NOT NULL;
ALTER TABLE loan_regulation ADD COLUMN guarantee_cap_no_house  BIGINT        NOT NULL;
ALTER TABLE loan_regulation ADD COLUMN guarantee_cap_one_house BIGINT        NOT NULL;

COMMENT ON COLUMN loan_regulation.dti_limit IS 'DTI 참고 수치(%) — 한도 판정에 미사용';
COMMENT ON COLUMN loan_regulation.stress_dsr_rate IS '스트레스 금리 가산율(%p) — 참고 한도 계산용, 최종 한도 미반영';

-- 시드 — 서울(규제지역) 한 행. 확인일 2026-09-13.
--   deposit_ratio_limit 80       — 한국주택금융공사 일반전세자금보증 안내 · 소요자금 임차보증금 × 80%
--   guarantee_cap_no_house 4억   — 같음 · 보증과목별 한도 4억(보증잔액 차감 전)
--   guarantee_cap_one_house 1.8억 — 같음 · 1주택자 수도권 · 규제지역
--   dsr_limit 40                 — 금융위원회 스트레스 DSR 3단계 보도자료 2025-05-20 · 은행권 차주단위 DSR
--                                  1주택자 수도권 · 규제지역 전세대출 이자상환분 DSR 반영은 2025-10-15 방안(2025-10-29 시행)
--   stress_dsr_rate 3.00         — 금융위원회 2025-10-15 · 수도권 · 규제지역 주담대 스트레스 금리 하한. 전세대출 적용 문장
--                                  미확인이라 참고 한도에만 쓴다
--   dti_limit 40                 — 참고 수치
--   effective_date 2025-10-29    — 전세대출 이자상환분 DSR 반영 시행일
INSERT INTO loan_regulation (house_type, region_type, dsr_limit, stress_dsr_rate, dti_limit, effective_date,
                             deposit_ratio_limit, guarantee_cap_no_house, guarantee_cap_one_house)
VALUES ('ALL', 'SEOUL_REGULATED', 40.00, 3.00, 40.00, DATE '2025-10-29', 80.00, 400000000, 180000000);

-- 시드 — 대표 상품 1행. 「예시값」. 상품 추천(LOAN-03)은 차기이며 이 행은 한도 계산의 금리 · 상품 한도 입력용이다.
--   interest_rate 4.200 — 한국주택금융공사 전세대출금리 월별 공시 2026-08 · HF 보증 전세대출 국민은행 평균금리. 확인일 2026-09-13
--   max_limit 4억       — 보증과목별 한도와 같게 둔 예시
--   loan_term 2         — 년(설계서 18절 단위). 전세 계약 2년 예시
--   repayment_type      — BULLET. 1단계는 만기일시 하나(비즈니스 로직 정의서 6-1장)
--   rate_type           — VARIABLE 예시. 공시는 금리 유형을 구분하지 않는다
--   house_ownership_condition TRUE — 주택 보유자도 한도 계산 대상
INSERT INTO loan_product (bank_name, product_name, loan_type, interest_rate, rate_type, max_limit, loan_term,
                          repayment_type, income_condition, house_ownership_condition)
VALUES ('국민은행', 'HF 보증 전세자금대출(예시)', 'JEONSE', 4.200, 'VARIABLE', 400000000, 2,
        'BULLET', NULL, TRUE);
