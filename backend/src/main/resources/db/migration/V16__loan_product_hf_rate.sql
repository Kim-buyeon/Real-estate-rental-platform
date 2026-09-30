-- LOAN-01 대출 상품 금리를 한국주택금융공사(HF) 전세자금대출 금리 API 로 갱신한다 — 은행 · 주택 유형별 한 행.
-- 제공처: https://www.data.go.kr/data/15082044/openapi.do (확인일 2026-09-30).
--
-- 더하는 컬럼 셋은 API 가 채우는 행의 식별 · 대표 상품 선택에 쓴다. V10 시드 예시 행은 셋 다 NULL 로 남고, API 행이 없을 때만
-- 한도 계산의 대표 상품이 된다(LoanProductRepository).
--   house_type  — 매물 유형(APARTMENT · OFFICETEL). API 의 houseTycd 06 · 10 에 대응한다
--   base_month  — 금리의 기준월(그 달 1일). API 요청의 loanYm. 응답에는 기준월이 없다
--   loan_amount — 기준월 대출실행금액(원). API 의 loanAmt. 대표 상품 선택의 기준이다
ALTER TABLE loan_product ADD COLUMN house_type  VARCHAR(20);
ALTER TABLE loan_product ADD COLUMN base_month  DATE;
ALTER TABLE loan_product ADD COLUMN loan_amount BIGINT;

-- 금리 유형 · 대출 기간은 API 가 주지 않고, HF 일반전세자금보증 안내(hf.go.kr/ko/sub02/sub02_01_02.do, 확인일 2026-09-30)에도
-- 상품 값으로 적혀 있지 않다. 값을 지어내지 않도록 NULL 을 허용한다. 두 값은 한도 계산에 쓰이지 않는다(비즈니스 로직 정의서 6장).
ALTER TABLE loan_product ALTER COLUMN rate_type DROP NOT NULL;
ALTER TABLE loan_product ALTER COLUMN loan_term DROP NOT NULL;

-- 갱신의 자연키 — 같은 은행 · 주택 유형은 한 행이고, 새 기준월이 오면 그 행을 고친다. 시드 행(house_type NULL)은 걸리지 않는다.
CREATE UNIQUE INDEX uq_loan_product_bank_house_type ON loan_product (bank_name, house_type) WHERE house_type IS NOT NULL;

COMMENT ON COLUMN loan_product.house_type IS '매물 유형(APARTMENT/OFFICETEL). HF 금리 API 행만. 시드 행은 NULL';
COMMENT ON COLUMN loan_product.base_month IS '금리 기준월(그 달 1일). HF 금리 API 행만. 시드 행은 NULL';
COMMENT ON COLUMN loan_product.loan_amount IS '기준월 대출실행금액(원). 대표 상품 선택 기준';
