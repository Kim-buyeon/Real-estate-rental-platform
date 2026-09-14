// 회원 · 인증 API 명세 — 명세 표의 행 하나 = 함수 하나. 재발급은 client.ts의 reissue()가 담당한다
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
