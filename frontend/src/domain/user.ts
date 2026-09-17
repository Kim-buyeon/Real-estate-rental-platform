// 회원 도메인 상수 · 열거값 · 표시 문구. 입력 길이는 백엔드 LoginRequest · SignupRequest 검증 값과 같다 —
// 클라이언트 검증은 보조이고 정본은 서버 error.field다.
export const EMAIL_MAX_LENGTH = 100;
export const NAME_MAX_LENGTH = 50;
export const PHONE_MAX_LENGTH = 20;

/** 신용점수 상한 — 백엔드 ProfileUpdateRequest의 검증 값(국내 개인 신용점수 만점). 0은 미입력이다 */
export const CREDIT_SCORE_MAX = 1000;

/** 권한 — 회원·인증 API 명세 1.1 account.role. 값은 명세의 문자열 그대로다 */
export const ROLES = ['USER', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export const ROLE_LABEL: Record<Role, string> = {
  USER: '일반 회원',
  ADMIN: '관리자',
};

/**
 * 자격 정보 필드 — 명세 1.1 profile 표의 여섯 필드. 프로필 응답의 missingFields가 이 필드명으로 온다.
 * 명세가 missingFields의 값 목록을 따로 주지 않아, 값의 출처는 같은 표의 필드명이다.
 */
export const PROFILE_FIELDS = [
  'annualIncome',
  'creditScore',
  'existingLoan',
  'existingLoanAnnualPayment',
  'hasHouse',
  'ownFund',
] as const;
export type ProfileField = (typeof PROFILE_FIELDS)[number];

/** 문구는 명세 1.1 profile 표의 설명과 같은 말을 쓴다 — 미입력 안내와 입력 라벨이 한 이름으로 불리게 */
export const PROFILE_FIELD_LABEL: Record<ProfileField, string> = {
  annualIncome: '연 소득',
  creditScore: '신용점수',
  existingLoan: '기존 대출 잔액',
  existingLoanAnnualPayment: '기존 대출 연간 상환액',
  hasHouse: '주택 보유 여부',
  ownFund: '자기자금',
};

// 서버가 우리가 모르는 코드를 보내도 화면이 빈칸이 되지 않게 코드 문자열을 그대로 보여준다 —
// domain/risk.ts의 labelOf와 같은 방식이다. missingFields가 명세에 목록 없는 string[]이라 특히 그렇다
function labelOf(labels: Record<string, string>, code: string): string {
  return labels[code] ?? code;
}

export const roleLabel = (role: string) => labelOf(ROLE_LABEL, role);
/** missingFields 항목 · 자격 정보 입력의 표시 문구 */
export const profileFieldLabel = (field: string) => labelOf(PROFILE_FIELD_LABEL, field);
