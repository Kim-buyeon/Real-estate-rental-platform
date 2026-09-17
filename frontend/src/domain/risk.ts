// 위험 등급 열거값 · 표시 문구 · 색 토큰. 등급에서 시각 요소로 가는 매핑은 전부 여기다
// (frontend/CLAUDE.md 타입·열거값). 값은 매물 API 명세 1.4 · 위험도 명세와 백엔드 RiskGrade 그대로다.

import { formatPercent } from '../lib/format';

export const RISK_GRADES = ['SAFE', 'CAUTION', 'DANGER'] as const;
export type RiskGrade = (typeof RISK_GRADES)[number];

export const RISK_GRADE_LABEL: Record<RiskGrade, string> = {
  SAFE: '안전',
  CAUTION: '주의',
  DANGER: '위험',
};

/** 색 값(hex)은 여기 없다 — tokens.css의 --color-risk-* 와 Badge 변형 이름이 갖는다 */
export const RISK_GRADE_TOKEN: Record<RiskGrade, 'risk-safe' | 'risk-caution' | 'risk-danger'> = {
  SAFE: 'risk-safe',
  CAUTION: 'risk-caution',
  DANGER: 'risk-danger',
};

/** 미분석 — 분석 이력이 없어 riskGrade가 null인 상태 (명세 1.4 마지막 줄). 열거값이 아니므로 매핑 밖에 둔다 */
export const UNANALYZED_LABEL = '미분석';
export const UNANALYZED_TOKEN = 'risk-unanalyzed';

/** null(미분석) 처리는 여기서 한다 — 컴포넌트마다 `grade ?? …`를 적지 않는다 */
export const riskGradeLabel = (grade: RiskGrade | null) => (grade ? RISK_GRADE_LABEL[grade] : UNANALYZED_LABEL);
export const riskGradeToken = (grade: RiskGrade | null) => (grade ? RISK_GRADE_TOKEN[grade] : UNANALYZED_TOKEN);

/**
 * 전세가율 문구. 값이 없으면(미분석) UNANALYZED_LABEL이다 — 컴포넌트가 문구를 다시 적거나
 * 조건 분기를 두지 않게 등급 문구와 같은 방식으로 여기서 처리한다.
 * undefined까지 받는 이유: 매물 상세는 riskSummary 자체가 null이라(api/property.ts) 호출부가
 * 옵셔널 체이닝으로 넘긴다. 마커는 debtRatio가 바로 null이다(명세 1.4).
 * 값 없음 처리를 lib/format.ts의 formatPercent에 넣지 않는다 — 그쪽은 도메인을 모르는 순수 함수다.
 */
export const debtRatioLabel = (debtRatio: number | null | undefined) =>
  debtRatio === null || debtRatio === undefined ? UNANALYZED_LABEL : formatPercent(debtRatio);

// ── 판정 근거의 열거값 (위험도 API 명세 1.1) ──────────────────────────────
// 값은 명세와 백엔드 enum 상수명 그대로다. 문구도 백엔드 enum의 label과 같은 말을 쓴다 —
// 같은 코드가 화면과 서버에서 다른 이름으로 불리지 않게. 임계 수치(70 · 80 등)는 문구에 넣지 않는다:
// 기준은 판정 기준 문서와 DB(RISK_CRITERIA)가 갖는다.

/** 등급 결정 사유 — 명세 1.1 gradeReason */
export const GRADE_REASONS = [
  'NEGATIVE_EQUITY',
  'INSURANCE_INELIGIBLE',
  'LEASE_RATIO_CAUTION',
  'INSURANCE_ELIGIBLE',
] as const;
export type GradeReason = (typeof GRADE_REASONS)[number];

export const GRADE_REASON_LABEL: Record<GradeReason, string> = {
  NEGATIVE_EQUITY: '깡통전세 해당',
  INSURANCE_INELIGIBLE: '3사 가입 불가',
  LEASE_RATIO_CAUTION: '전세가율 주의 구간',
  INSURANCE_ELIGIBLE: '보증보험 가입 가능',
};

/** 보증기관 — 명세 1.1 providers[].provider. 규약 도메인 용어 표의 `provider` */
export const GUARANTEE_PROVIDERS = ['HUG', 'HF', 'SGI'] as const;
export type GuaranteeProvider = (typeof GUARANTEE_PROVIDERS)[number];

export const GUARANTEE_PROVIDER_LABEL: Record<GuaranteeProvider, string> = {
  HUG: '주택도시보증공사',
  HF: '한국주택금융공사',
  SGI: '서울보증보험',
};

/** 위배된 집 단위 조건 — 명세 1.1 providers[].failedConditions[] */
export const GUARANTEE_FAILED_CONDITIONS = [
  'DEBT_RATIO_EXCEEDED',
  'SENIOR_DEBT_RATIO_EXCEEDED',
  'DEPOSIT_LIMIT_EXCEEDED',
  'VIOLATION_BUILDING',
  'RIGHT_VIOLATION',
  'OWNER_MISMATCH',
  'ADDRESS_MISMATCH',
] as const;
export type GuaranteeFailedCondition = (typeof GUARANTEE_FAILED_CONDITIONS)[number];

export const GUARANTEE_FAILED_CONDITION_LABEL: Record<GuaranteeFailedCondition, string> = {
  DEBT_RATIO_EXCEEDED: '전세가율 초과',
  SENIOR_DEBT_RATIO_EXCEEDED: '선순위채권 한도 초과',
  DEPOSIT_LIMIT_EXCEEDED: '보증금 한도 초과',
  VIOLATION_BUILDING: '위반건축물',
  RIGHT_VIOLATION: '권리 침해',
  OWNER_MISMATCH: '명의 불일치',
  ADDRESS_MISMATCH: '주소 불일치',
};

/** 시스템이 판정하지 않는 개인 자격 확인 사항 — 명세 1.1 personalConditions[] */
export const PERSONAL_CONDITIONS = [
  'ANNUAL_INCOME',
  'APPLICATION_DEADLINE',
  'NEW_OR_RENEWAL',
  'RESIDENTIAL_USE_NOTATION',
  'BROKER_CONTRACT',
  'MOVE_IN_AND_FIXED_DATE',
] as const;
export type PersonalCondition = (typeof PERSONAL_CONDITIONS)[number];

export const PERSONAL_CONDITION_LABEL: Record<PersonalCondition, string> = {
  ANNUAL_INCOME: '연소득 기준',
  APPLICATION_DEADLINE: '신청기한',
  NEW_OR_RENEWAL: '신규 · 갱신 계약 구분',
  RESIDENTIAL_USE_NOTATION: '주거용 표기',
  BROKER_CONTRACT: '공인중개사 계약',
  MOVE_IN_AND_FIXED_DATE: '전입신고 · 확정일자',
};

/**
 * 갑구 권리 유형 — 명세 1.3 ownerships[].rightType. 위험도 응답의 rightViolations[](압류 · 가압류 ·
 * 경매개시결정 · 신탁)와 warnings[](가등기 · 임차권등기명령)가 이 열거값으로 온다 — 명세 1.1.
 * 어느 항목이 침해이고 어느 것이 경고인지는 서버가 정해 두 배열로 나눠 준다. 화면에서 다시 판정하지 않는다.
 */
export const OWNERSHIP_RIGHT_TYPES = [
  'OWNERSHIP_PRESERVATION',
  'OWNERSHIP_TRANSFER',
  'SEIZURE',
  'PROVISIONAL_SEIZURE',
  'AUCTION_COMMENCEMENT',
  'TRUST',
  'PROVISIONAL_REGISTRATION',
  'TENANCY_REGISTRATION_ORDER',
] as const;
export type OwnershipRightType = (typeof OWNERSHIP_RIGHT_TYPES)[number];

export const OWNERSHIP_RIGHT_TYPE_LABEL: Record<OwnershipRightType, string> = {
  OWNERSHIP_PRESERVATION: '소유권보존',
  OWNERSHIP_TRANSFER: '소유권이전',
  SEIZURE: '압류',
  PROVISIONAL_SEIZURE: '가압류',
  AUCTION_COMMENCEMENT: '경매개시결정',
  TRUST: '신탁',
  PROVISIONAL_REGISTRATION: '가등기',
  TENANCY_REGISTRATION_ORDER: '임차권등기명령',
};

/**
 * 시세 산출 근거 — 명세 1.1 · 매물 명세 1.7 priceType. 두 응답이 같은 값을 쓰므로 한 곳에만 둔다.
 * 지금 적재 경로가 만들어 내는 값은 실거래가 하나다(백엔드 PriceType). 값이 늘면 함께 추가한다.
 */
export const PRICE_TYPES = ['ACTUAL_TRANSACTION'] as const;
export type PriceType = (typeof PRICE_TYPES)[number];

export const PRICE_TYPE_LABEL: Record<PriceType, string> = {
  ACTUAL_TRANSACTION: '실거래가',
};

// ── 표시 문구 헬퍼 ────────────────────────────────────────────────────────
// 서버가 우리가 모르는 코드를 보내도 화면이 빈칸이 되지 않게 코드 문자열을 그대로 보여준다.
// (백엔드에 값이 늘고 프론트가 아직 따라가지 않은 때 — 그 사실이 화면에 드러나야 고칠 수 있다)

function labelOf(labels: Record<string, string>, code: string): string {
  return labels[code] ?? code;
}

// 파라미터를 열거 유니언이 아니라 string으로 받는다 — 모르는 코드를 그대로 돌려주는 것이 이 함수들의 일이고,
// 유니언으로 좁히면 그 경우를 호출하는 쪽에서 단언해야 한다

export const gradeReasonLabel = (reason: string) => labelOf(GRADE_REASON_LABEL, reason);
export const guaranteeProviderLabel = (provider: string) => labelOf(GUARANTEE_PROVIDER_LABEL, provider);
export const guaranteeFailedConditionLabel = (condition: string) =>
  labelOf(GUARANTEE_FAILED_CONDITION_LABEL, condition);
export const personalConditionLabel = (condition: string) => labelOf(PERSONAL_CONDITION_LABEL, condition);
/** rightViolations[] · warnings[]의 항목 문구. 두 배열이 같은 열거값을 쓴다 */
export const ownershipRightTypeLabel = (rightType: string) => labelOf(OWNERSHIP_RIGHT_TYPE_LABEL, rightType);
export const priceTypeLabel = (priceType: string) => labelOf(PRICE_TYPE_LABEL, priceType);
