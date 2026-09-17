// 대출 도메인 MSW 핸들러. 응답은 대출 API 명세 1.1의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { LoanLimit } from '../../../api/loan';

/**
 * GET /api/loans/limit 응답 예시 그대로 — 명세 1.1. 주택 보유자이며 DSR 기준 한도(95,238,095원)가
 * 셋 중 가장 작아 최종 한도를 결정한다: min(160,000,000 · 180,000,000 · 95,238,095) = 95,238,095
 * (dsrLimit) → appliedRegulation 'DSR' · finalLimit 95,238,095. 명세 예시 그대로라 정합은 명세가 보증한다.
 */
export const LOAN_LIMIT: LoanLimit = {
  depositLimit: 160000000,
  guaranteeCapLimit: 180000000,
  dsrLimit: 95238095,
  stressDsrLimit: 55555555,
  finalLimit: 95238095,
  appliedRegulation: 'DSR',
  dtiReference: 40.0,
  missingFields: [],
};

/**
 * 무주택자 응답 — 명세 1.1 「dsrLimit … 주택 보유자만, 무주택이면 null」 · 「stressDsrLimit … 무주택이면
 * null」. 비즈니스 로직 6장 계산 의사코드의 candidates 배열은 무주택이면 DSR 항목 자체를 넣지 않으므로
 * (`DSR(주택 보유자만)`), appliedRegulation은 DEPOSIT_RATIO · GUARANTEE_CAP · PRODUCT_LIMIT 중 하나여야
 * 하고 DSR일 수 없다.
 *
 * 여기서는 보증금 기준 한도(200,000,000)가 보증기관 상한(400,000,000, 무주택 guarantee_cap_no_house)보다
 * 작아 DEPOSIT_RATIO가 결정한다 — finalLimit = min(depositLimit, guaranteeCapLimit) = depositLimit.
 * dtiReference는 DSR·주택 보유 여부와 무관하게 연소득만으로 계산되므로(6장) 무주택이어도 값을 가질 수 있다.
 */
export const LOAN_LIMIT_NO_HOUSE: LoanLimit = {
  depositLimit: 200000000,
  guaranteeCapLimit: 400000000,
  dsrLimit: null,
  stressDsrLimit: null,
  finalLimit: 200000000,
  appliedRegulation: 'DEPOSIT_RATIO',
  dtiReference: 12.3,
  missingFields: [],
};

/**
 * GET /api/loans/limit 422 PROFILE_INCOMPLETE 오류 봉투 — 공통 규약 1.2의 실패 응답 예시가 바로 이
 * 시나리오(PROFILE_INCOMPLETE · field annualIncome)라 문구를 그대로 옮긴다. 명세 1.1 「주택 보유자인데
 * 연소득이 없으면 422 PROFILE_INCOMPLETE, field에 annualIncome」과도 맞는다.
 */
export const LOAN_PROFILE_INCOMPLETE_ERROR = {
  code: 'PROFILE_INCOMPLETE',
  message: '대출 한도 계산에 필요한 자격 정보가 없습니다.',
  field: 'annualIncome',
};

/**
 * GET /api/loans/limit 422 LOAN_PROPERTY_NOT_ELIGIBLE 오류 봉투 — 명세 1.1 「보증보험 가입이 불가한
 * 매물이면 422 LOAN_PROPERTY_NOT_ELIGIBLE」 · 공통 규약 2장 오류 코드 표의 설명을 그대로 문구로 옮긴다.
 */
export const LOAN_PROPERTY_NOT_ELIGIBLE_ERROR = {
  code: 'LOAN_PROPERTY_NOT_ELIGIBLE',
  message: '보증보험 가입이 불가한 매물로 대출 한도를 계산할 수 없습니다.',
};

/** 기본(정상 · 주택 보유자) 핸들러 */
export const loanHandlers = [
  http.get('/api/loans/limit', () => HttpResponse.json({ success: true, data: LOAN_LIMIT })),
];

/** 무주택 핸들러 — server.use(...loanNoHouseHandlers)로 기본 핸들러를 대체한다 */
export const loanNoHouseHandlers = [
  http.get('/api/loans/limit', () => HttpResponse.json({ success: true, data: LOAN_LIMIT_NO_HOUSE })),
];

/** 422 PROFILE_INCOMPLETE 핸들러 */
export const loanProfileIncompleteHandlers = [
  http.get('/api/loans/limit', () =>
    HttpResponse.json({ success: false, error: LOAN_PROFILE_INCOMPLETE_ERROR }, { status: 422 }),
  ),
];

/** 422 LOAN_PROPERTY_NOT_ELIGIBLE 핸들러 */
export const loanNotEligibleHandlers = [
  http.get('/api/loans/limit', () =>
    HttpResponse.json({ success: false, error: LOAN_PROPERTY_NOT_ELIGIBLE_ERROR }, { status: 422 }),
  ),
];
