// 위험도 도메인 MSW 핸들러. 응답은 위험도 API 명세 1.1 · 1.3 · 1.4의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { Registry, RiskAnalysis, RiskReanalyzeResult } from '../../../api/risk';

/** 위험도 API 명세 1.1 응답 예시 그대로 — PropertyDetailPanel 테스트가 쓴다 */
export const RISK_ANALYSIS: RiskAnalysis = {
  riskGrade: 'DANGER',
  gradeReason: 'NEGATIVE_EQUITY',
  debtRatio: 116.7,
  marketPrice: 300000000,
  priceType: 'ACTUAL_TRANSACTION',
  priceDate: '2026-06-30',
  seniorDebtTotal: 250000000,
  isNegativeEquity: true,
  insuranceEligible: false,
  providers: [
    {
      provider: 'HUG',
      eligible: false,
      failedConditions: ['DEBT_RATIO_EXCEEDED', 'SENIOR_DEBT_RATIO_EXCEEDED'],
      loanLinkRequired: false,
      guaranteeLimit: 20000000,
      estimatedPremium: null,
      productName: null,
    },
    {
      provider: 'HF',
      eligible: false,
      failedConditions: ['DEBT_RATIO_EXCEEDED'],
      loanLinkRequired: true,
      guaranteeLimit: 20000000,
      estimatedPremium: null,
      productName: null,
    },
    {
      provider: 'SGI',
      eligible: false,
      failedConditions: ['DEBT_RATIO_EXCEEDED'],
      loanLinkRequired: false,
      guaranteeLimit: 20000000,
      estimatedPremium: null,
      productName: null,
    },
  ],
  personalConditions: [
    'ANNUAL_INCOME',
    'APPLICATION_DEADLINE',
    'NEW_OR_RENEWAL',
    'RESIDENTIAL_USE_NOTATION',
    'BROKER_CONTRACT',
    'MOVE_IN_AND_FIXED_DATE',
  ],
  rightViolations: [],
  warnings: ['PROVISIONAL_REGISTRATION'],
  consistency: {
    ownerNameMatched: true,
    addressMatched: true,
    violationBuilding: false,
    areaMatched: true,
  },
  analyzedAt: '2026-07-29T03:00:00+09:00',
};

/** 위험도 API 명세 1.3 응답 예시 그대로 — RegistryTimeline 테스트가 쓴다 */
export const REGISTRY: Registry = {
  propertyId: 1024,
  ownerships: [
    {
      rankNo: 2,
      rightType: 'OWNERSHIP_TRANSFER',
      holderName: '김임대',
      receivedDate: '2019-03-11',
      cause: '매매',
      isActive: true,
    },
  ],
  mortgages: [
    {
      rankNo: 1,
      creditor: '○○은행',
      maxClaimAmount: 250000000,
      receivedDate: '2019-03-11',
      isActive: true,
    },
  ],
  collectedAt: '2026-07-29T03:00:00+09:00',
  dataSource: 'MOCK',
};

/** 위험도 API 명세 1.4 응답 예시 그대로 — 등급이 바뀐 경우다 */
export const REANALYZE_RESULT: RiskReanalyzeResult = {
  propertyId: 1024,
  previousGrade: 'CAUTION',
  riskGrade: 'DANGER',
  gradeChanged: true,
  analyzedAt: '2026-07-29T10:12:00+09:00',
};

/**
 * REANALYZE_RESULT가 다시 그린 뒤의 위험도 조회 응답 — RISK_ANALYSIS와 같은 매물의 재분석 「이후」
 * 상태다. gradeChanged 검증(PropertyDetailPanel.test.tsx)에서 재분석 뒤 RiskVerdict가 실제로 새
 * 등급을 그리는지 보려면 GET /risk가 두 번째 호출부터 다른 값을 줘야 한다.
 *
 * riskGrade는 CAUTION으로 고정한다 — 이 픽스처의 존재 이유다. RISK_ANALYSIS(DANGER · 「위험」)와도
 * PROPERTY_DETAIL의 riskSummary.riskGrade(SAFE · 「안전」)와도 문구가 겹치지 않아야 한다 — 겹치면
 * 두 배지가 같은 문구라 재조회로 바뀐 것인지 원래 있던 문구인지 구분할 수 없다.
 *
 * 리뷰 #91 F10 — providers 전부가 eligible: false인 채로 insuranceEligible: true였고(명세 1.1 —
 * 3사 중 하나 이상), failedConditions가 낡았으며(담보인정비율 90%에서 안 걸리는 DEBT_RATIO_EXCEEDED가
 * 남아 있었다), debtRatio 82.3이 isNegativeEquity: false · CAUTION과 모순됐다(82.3 > 80이면
 * 깡통전세). 아래는 그 재계산이고, 값은 marketPrice(3억, RISK_ANALYSIS에서 그대로 스프레드)를
 * 고정하고 business-logic.md §2~§4 의사코드로 검산했다.
 *
 * - debtRatio 78.5(전세가율) — §3 등급 기준: CAUTION은 70 < 전세가율 ≤ 80
 *   (RISK_CRITERIA.caution_lease_ratio=70 · negative_equity_ratio=80, §3 등급 기준의 경계값). §4가 깡통전세 ⇔
 *   전세가율 > 80이라고 정하므로(§4 「전세가율과 같은 판정이다」) 78.5(≤80)는 isNegativeEquity: false와 맞다.
 * - seniorDebtTotal 2억 — §2 판정 의사코드의 DEBT_RATIO_EXCEEDED·SENIOR_DEBT_RATIO_EXCEEDED 조건: 선순위채권/주택가액이 HUG 선순위한도
 *   60%(GUARANTEE_CRITERIA.senior_debt_ratio_limit)를 넘는지가 기관을 가르는 유일한 축이다(§2 판정 절차의 선순위채권 조건 —
 *   담보인정비율은 3사 90%로 같고, HF·SGI 선순위한도는 미확정이라 이 조건 자체를 검사하지 않는다).
 *   2억/3억=66.7%는 HUG의 60%는 넘지만 HF·SGI는 검사 대상이 아니라 걸리지 않는다
 *   → HUG만 SENIOR_DEBT_RATIO_EXCEEDED.
 * - DEBT_RATIO_EXCEEDED는 세 기관 모두 붙지 않는다 — §2 판정 의사코드의 DEBT_RATIO_EXCEEDED 조건은 전세가율(=debtRatio) > 담보인정
 *   비율 90이고 78.5 ≤ 90이다.
 * - DEPOSIT_LIMIT_EXCEEDED도 붙지 않는다 — 전세보증금은 debtRatio·seniorDebtTotal·marketPrice로
 *   역산하면 약 3,550만 원(78.5%×3억−2억)이고 HUG·HF 수도권 7억 · SGI 10억(§2 판정 절차의 보증금 한도)에 크게 못 미친다.
 * - VIOLATION_BUILDING · RIGHT_VIOLATION · OWNER_MISMATCH · ADDRESS_MISMATCH는 RISK_ANALYSIS에서
 *   그대로 스프레드된 consistency(전부 일치) · rightViolations(빈 배열)와 맞게 어느 기관에도 없다.
 * - insuranceEligible: true — 위 결과로 HF·SGI가 가입 가능해 3사 중 하나 이상(명세 1.1)을 만족한다.
 *   HUG 「한 기관만」 가입 가능한 매물로는 만들 수 없었다 — 3사 담보인정비율이 모두 90%로 같고(§2 판정 절차의 전세가율 조건)
 *   선순위한도는 HUG만 값이 있어(§2 판정 절차의 선순위채권 조건) 기관을 가르는 축이 그것 하나뿐이기 때문이다. 그래서 방침을
 *   「HUG만 불가 · HF·SGI 가능」으로 바꿨다(리뷰 #91 F10).
 * - guaranteeLimit(3사 공통 7,000만) = 3억 × 0.9(담보인정비율, 3사 동일) − 2억(선순위채권) — 명세
 *   1.1 guaranteeLimit 정의(주택가격 × 담보인정비율 − 선순위채권). RISK_ANALYSIS의 guaranteeLimit
 *   2,000만(= 3억×0.9−2.5억)이 같은 식의 다른 예다.
 * - estimatedPremium · productName은 명세·business-logic.md 어느 쪽도 값을 정하지 않는다. 여기
 *   HF·SGI에 넣은 값은 판정에 쓰이지 않는 **픽스처 전용 임의값**이며, 실제 보증료율을 반영한 것이
 *   아니다. 상품명은 실제 통용되는 명칭을 빌렸을 뿐 명세가 정한 문자열이 아니다.
 * - gradeReason LEASE_RATIO_CAUTION — 명세 1.1: 「전세가율이 CAUTION 경계 초과」. INSURANCE_INELIGIBLE
 *   이 아닌 것은 insuranceEligible: true와 맞다.
 */
export const RISK_ANALYSIS_AFTER_REANALYSIS: RiskAnalysis = {
  ...RISK_ANALYSIS,
  riskGrade: 'CAUTION',
  gradeReason: 'LEASE_RATIO_CAUTION',
  debtRatio: 78.5,
  seniorDebtTotal: 200000000,
  isNegativeEquity: false,
  insuranceEligible: true,
  providers: [
    {
      provider: 'HUG',
      eligible: false,
      failedConditions: ['SENIOR_DEBT_RATIO_EXCEEDED'],
      loanLinkRequired: false,
      guaranteeLimit: 70000000,
      estimatedPremium: null,
      productName: null,
    },
    {
      provider: 'HF',
      eligible: true,
      failedConditions: [],
      loanLinkRequired: true,
      guaranteeLimit: 70000000,
      // 픽스처 전용 임의값 — business-logic.md는 보증료 산정식을 정하지 않는다
      estimatedPremium: 45000,
      productName: '전세지킴보증',
    },
    {
      provider: 'SGI',
      eligible: true,
      failedConditions: [],
      loanLinkRequired: false,
      guaranteeLimit: 70000000,
      // 픽스처 전용 임의값 — business-logic.md는 보증료 산정식을 정하지 않는다
      estimatedPremium: 60000,
      productName: '전세금보장신용보증',
    },
  ],
  analyzedAt: '2026-07-29T10:12:00+09:00',
};

/**
 * 첫 분석의 재분석 응답 — 명세 1.2 「previousGrade가 null(첫 분석)이면 gradeChanged는 false다」.
 * gradeChanged가 거짓일 때 property 루트 전체를 무효화하지 않는 분기(queries/risk.ts)를
 * 확인하는 자연스러운 픽스처다.
 */
export const REANALYZE_RESULT_FIRST_ANALYSIS: RiskReanalyzeResult = {
  propertyId: 1024,
  previousGrade: null,
  riskGrade: 'SAFE',
  gradeChanged: false,
  analyzedAt: '2026-07-29T10:12:00+09:00',
};

export const riskHandlers = [
  http.get('/api/properties/:propertyId/risk', () => HttpResponse.json({ success: true, data: RISK_ANALYSIS })),
  http.get('/api/properties/:propertyId/registry', () => HttpResponse.json({ success: true, data: REGISTRY })),
  http.post('/api/properties/:propertyId/risk/reanalyze', () =>
    HttpResponse.json({ success: true, data: REANALYZE_RESULT }),
  ),
];
