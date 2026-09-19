// 회원 도메인 MSW 핸들러. 응답은 회원·인증 API 명세 1.1 · 1.3의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { Profile } from '../../../api/user';

/**
 * GET /api/me/profile 응답 예시 그대로 — 명세 1.1. missingFields가 비어 있고 자격 정보가 모두 채워진
 * 상태다. existingLoan · existingLoanAnnualPayment는 값이 0이지만(기존 대출 없음) missingFields에는
 * 없다 — 값 0이 곧 미입력은 아니라는 것을 이 픽스처 자체가 보인다 (ProfileForm.test.tsx가 이 정합을 쓴다).
 */
export const PROFILE: Profile = {
  account: {
    name: '홍길동',
    phone: '010-1234-5678',
    email: 'user@example.com',
    role: 'USER',
    createdAt: '2026-07-01T09:12:00+09:00',
  },
  profile: {
    annualIncome: 42000000,
    creditScore: 820,
    existingLoan: 0,
    existingLoanAnnualPayment: 0,
    hasHouse: false,
    ownFund: 50000000,
  },
  missingFields: [],
};

/**
 * 자격 정보가 미입력인 상태 — 명세 1.1 「자격 정보 중 미입력 항목은 값을 0 또는 null로 전달한다 …
 * missingFields로 확인할 수 있다」. 서버가 missingFields에 담는 것은 annualIncome · creditScore 둘이고
 * (UserQueryService.missingFields), 그 값도 0으로 맞춘다. existingLoan · existingLoanAnnualPayment ·
 * ownFund도 값은 0이지만 missingFields에는 없다 — 기존 대출이 없거나 자기자금이 0인 정상 상태와 미입력을
 * 값만으로 구분할 수 없어 서버가 넣지 않는 항목들이다. 화면이 스스로 판정하지 않는 이유를 missingFields에
 * 없는 0 값 필드를 함께 둬서 픽스처로도 보인다.
 */
export const PROFILE_INCOMPLETE: Profile = {
  account: PROFILE.account,
  profile: {
    annualIncome: 0,
    creditScore: 0,
    existingLoan: 0,
    existingLoanAnnualPayment: 0,
    hasHouse: false,
    ownFund: 0,
  },
  missingFields: ['annualIncome', 'creditScore'],
};

/** POST /api/auth/password-reset/confirm 요청 예시의 토큰 — 명세 1.3 */
export const PASSWORD_RESET_TOKEN = 'Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl';

export const userHandlers = [
  http.get('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE })),
  http.put('/api/me/profile', () => HttpResponse.json({ success: true, data: PROFILE })),
  // 명세 1.3 — 요청은 가입 여부와 무관하게 항상 200 · data null, 확정 성공도 200 · data null
  http.post('/api/auth/password-reset', () => HttpResponse.json({ success: true, data: null })),
  http.post('/api/auth/password-reset/confirm', () => HttpResponse.json({ success: true, data: null })),
];
