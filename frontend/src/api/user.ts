// 회원 · 인증 API 명세 — 명세 표의 행 하나 = 함수 하나. 재발급은 client.ts의 reissue()가 담당한다
import type { Role } from '../domain/user';
import { request, type AuthTokens } from './client';

/** POST /api/auth/login 요청 — 명세 1.2 */
export interface LoginForm {
  email: string;
  password: string;
}

/** POST /api/auth/signup 요청 — 명세 1.2. phone은 필수가 아니다(백엔드 SignupRequest) */
export interface SignupForm {
  email: string;
  password: string;
  name: string;
  phone?: string;
}

export const login = (form: LoginForm) =>
  request<AuthTokens>({ method: 'POST', url: '/auth/login', data: form });

/** 성공 시 data는 null — 명세 1.2 */
export const signup = (form: SignupForm) =>
  request<null>({ method: 'POST', url: '/auth/signup', data: form });

/** 본문 없음. 성공 시 data는 null — 명세 1.2 */
export const logout = () => request<null>({ method: 'POST', url: '/auth/logout' });

// ── USER-06 비밀번호 재설정 (명세 1.3) ──────────────────────────────────

/** POST /api/auth/password-reset/confirm 요청 — 명세 1.3. token은 메일 링크의 쿼리에서 읽은 값 그대로다 */
export interface PasswordResetConfirmForm {
  token: string;
  newPassword: string;
}

/** 가입 여부와 무관하게 항상 200 · data null — 명세 1.3. 형식 오류만 400 INVALID_REQUEST */
export const requestPasswordReset = (email: string) =>
  request<null>({ method: 'POST', url: '/auth/password-reset', data: { email } });

/** 성공 시 data는 null. 무효 토큰은 400 AUTH_RESET_TOKEN_INVALID, 비밀번호 규칙 위반은 400 INVALID_REQUEST + field newPassword — 명세 1.3 */
export const confirmPasswordReset = (token: string, newPassword: string) =>
  request<null>({
    method: 'POST',
    url: '/auth/password-reset/confirm',
    data: { token, newPassword } satisfies PasswordResetConfirmForm,
  });

// ── USER-03 프로필 (명세 1.1) ───────────────────────────────────────────
// 조회와 수정이 같은 중첩 구조를 쓴다. 수정 요청에는 「수정 가능」으로 표시된 필드를 모두 담는다 —
// 바뀐 것만 보내는 부분 전송이 아니다. 수정 불가 필드가 섞이면 서버가 무시한다.

/** 계정 정보 — 명세 1.1 account. email · role · createdAt은 수정 불가다 */
export interface ProfileAccount {
  name: string;
  phone: string;
  email: string;
  role: Role;
  /** 가입일시 (ISO 8601) */
  createdAt: string;
}

/** 자격 정보 — 명세 1.1 profile. 여섯 필드가 모두 수정 가능이라 조회와 수정이 같은 형태다 */
export interface ProfileQualification {
  /** 연 소득 (원) */
  annualIncome: number;
  creditScore: number;
  /** 기존 대출 잔액 (원) */
  existingLoan: number;
  /** 기존 대출 연간 상환액 (원) */
  existingLoanAnnualPayment: number;
  hasHouse: boolean;
  /** 계약에 투입 가능한 자기자금 (원) */
  ownFund: number;
}

/** GET · PUT /api/me/profile 응답 — 명세 1.1 */
export interface Profile {
  account: ProfileAccount;
  profile: ProfileQualification;
  /**
   * 미입력 항목의 자격 정보 필드명. 무엇이 미입력인지는 서버가 정한다 — 화면은 읽어 표시만 하고
   * 값 0 · null을 보고 다시 판정하지 않는다. 명세가 값 목록을 주지 않아 string[]이며,
   * 표시 문구는 domain/user.ts의 profileFieldLabel이 모르는 코드까지 처리한다
   */
  missingFields: string[];
}

/** 수정 가능한 계정 정보 — 명세 1.1 account 표의 「가능」 두 필드 */
export interface ProfileAccountForm {
  name: string;
  phone: string;
}

/** PUT /api/me/profile 요청 — 명세 1.1 */
export interface ProfileForm {
  account: ProfileAccountForm;
  profile: ProfileQualification;
}

export const fetchProfile = () => request<Profile>({ url: '/me/profile' });

/** 응답은 조회와 같은 구조다 — 명세 1.1 「조회와 수정이 동일한 중첩 구조를 사용한다」 */
export const updateProfile = (profile: ProfileForm) =>
  request<Profile>({ method: 'PUT', url: '/me/profile', data: profile });
