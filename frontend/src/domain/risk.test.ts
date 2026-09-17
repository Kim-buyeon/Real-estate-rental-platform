// domain/risk.ts 표시 문구 매핑 테스트 — 알려진 코드는 문구로, 모르는 코드는 코드 그대로.
// 근거: docs/api/risk.md 1.1 열거값, frontend/CLAUDE.md 타입 · 열거값(표시 문구는 열거값에 붙인 매핑이 갖는다).

import { describe, expect, test } from 'vitest';
import {
  GRADE_REASONS,
  GUARANTEE_FAILED_CONDITIONS,
  GUARANTEE_PROVIDERS,
  OWNERSHIP_RIGHT_TYPES,
  PERSONAL_CONDITIONS,
  PRICE_TYPES,
  debtRatioLabel,
  gradeReasonLabel,
  guaranteeFailedConditionLabel,
  guaranteeProviderLabel,
  ownershipRightTypeLabel,
  personalConditionLabel,
  priceTypeLabel,
  riskGradeLabel,
} from './risk';

/** 명세 1.1의 코드마다 문구가 있어야 한다 — 매핑이 비면 화면이 코드 문자열을 그대로 보여 준다 */
describe('알려진 코드 → 표시 문구', () => {
  test('등급 결정 사유 (gradeReason)', () => {
    expect(gradeReasonLabel('NEGATIVE_EQUITY')).toBe('깡통전세 해당');
    expect(gradeReasonLabel('INSURANCE_INELIGIBLE')).toBe('3사 가입 불가');
    expect(gradeReasonLabel('LEASE_RATIO_CAUTION')).toBe('전세가율 주의 구간');
    expect(gradeReasonLabel('INSURANCE_ELIGIBLE')).toBe('보증보험 가입 가능');
  });

  test('보증기관 (provider)', () => {
    expect(guaranteeProviderLabel('HUG')).toBe('주택도시보증공사');
    expect(guaranteeProviderLabel('HF')).toBe('한국주택금융공사');
    expect(guaranteeProviderLabel('SGI')).toBe('서울보증보험');
  });

  test('위배된 집 단위 조건 (failedConditions)', () => {
    expect(guaranteeFailedConditionLabel('DEBT_RATIO_EXCEEDED')).toBe('전세가율 초과');
    expect(guaranteeFailedConditionLabel('SENIOR_DEBT_RATIO_EXCEEDED')).toBe('선순위채권 한도 초과');
    expect(guaranteeFailedConditionLabel('DEPOSIT_LIMIT_EXCEEDED')).toBe('보증금 한도 초과');
  });

  test('개인 자격 확인 사항 (personalConditions)', () => {
    expect(personalConditionLabel('ANNUAL_INCOME')).toBe('연소득 기준');
    expect(personalConditionLabel('MOVE_IN_AND_FIXED_DATE')).toBe('전입신고 · 확정일자');
  });

  test('권리 침해 · 경고 항목 (rightViolations · warnings)', () => {
    expect(ownershipRightTypeLabel('SEIZURE')).toBe('압류');
    expect(ownershipRightTypeLabel('PROVISIONAL_SEIZURE')).toBe('가압류');
    expect(ownershipRightTypeLabel('AUCTION_COMMENCEMENT')).toBe('경매개시결정');
    expect(ownershipRightTypeLabel('TRUST')).toBe('신탁');
    expect(ownershipRightTypeLabel('PROVISIONAL_REGISTRATION')).toBe('가등기');
    expect(ownershipRightTypeLabel('TENANCY_REGISTRATION_ORDER')).toBe('임차권등기명령');
  });

  test('시세 산출 근거 (priceType)', () => {
    expect(priceTypeLabel('ACTUAL_TRANSACTION')).toBe('실거래가');
  });

  test('열거값 전부에 문구가 있다 — 코드가 그대로 노출되지 않는다', () => {
    const cases: [readonly string[], (code: string) => string][] = [
      [GRADE_REASONS, gradeReasonLabel],
      [GUARANTEE_PROVIDERS, guaranteeProviderLabel],
      [GUARANTEE_FAILED_CONDITIONS, guaranteeFailedConditionLabel],
      [PERSONAL_CONDITIONS, personalConditionLabel],
      [OWNERSHIP_RIGHT_TYPES, ownershipRightTypeLabel],
      [PRICE_TYPES, priceTypeLabel],
    ];
    for (const [codes, label] of cases) {
      for (const code of codes) expect(label(code)).not.toBe(code);
    }
  });
});

/** 백엔드에 값이 늘고 프론트가 아직 따라가지 않은 때 — 빈칸이 아니라 코드가 보여야 고칠 수 있다 */
describe('모르는 코드 → 코드 그대로', () => {
  test('매핑에 없는 코드를 그대로 돌려준다', () => {
    expect(gradeReasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(guaranteeProviderLabel('KHFC')).toBe('KHFC');
    expect(guaranteeFailedConditionLabel('AREA_MISMATCH')).toBe('AREA_MISMATCH');
    expect(personalConditionLabel('CREDIT_SCORE')).toBe('CREDIT_SCORE');
    expect(ownershipRightTypeLabel('EASEMENT')).toBe('EASEMENT');
    expect(priceTypeLabel('PUBLIC_PRICE')).toBe('PUBLIC_PRICE');
  });

  test('빈 문자열도 그대로다 — 없는 값을 지어내지 않는다', () => {
    expect(gradeReasonLabel('')).toBe('');
  });
});

/** 등급 문구는 이 슬라이스 전부터 있던 것이다 — 함께 회귀를 막는다 */
describe('위험 등급 문구', () => {
  test('등급과 미분석', () => {
    expect(riskGradeLabel('SAFE')).toBe('안전');
    expect(riskGradeLabel('CAUTION')).toBe('주의');
    expect(riskGradeLabel('DANGER')).toBe('위험');
    expect(riskGradeLabel(null)).toBe('미분석');
  });

  /** 전세가율도 값 없음(미분석)을 여기서 처리한다 — 컴포넌트가 문구를 다시 적지 않게 */
  test('전세가율과 미분석', () => {
    expect(debtRatioLabel(68)).toBe('68%');
    expect(debtRatioLabel(0)).toBe('0%');
    expect(debtRatioLabel(null)).toBe('미분석');
    expect(debtRatioLabel(undefined)).toBe('미분석');
  });
});
