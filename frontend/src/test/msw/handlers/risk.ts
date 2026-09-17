// 위험도 도메인 MSW 핸들러. 응답은 위험도 API 명세 1.1의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { RiskAnalysis } from '../../../api/risk';

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

export const riskHandlers = [
  http.get('/api/properties/:propertyId/risk', () => HttpResponse.json({ success: true, data: RISK_ANALYSIS })),
];
