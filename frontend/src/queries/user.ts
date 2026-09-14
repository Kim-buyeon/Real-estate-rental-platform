// 회원 · 인증 뮤테이션. 쿼리 정의(userQueries)는 프로필 조회(USER-03) 슬라이스가 추가한다
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { endSession, startSession, type ApiError, type AuthTokens } from '../api/client';
import { login, logout, signup, type LoginForm, type SignupForm } from '../api/user';

// 오류 타입을 ApiError로 둔다 — client가 어떤 실패든 ApiError로 바꿔 던진다. 폼은 error.field로 입력을 고른다

/** 로그인 성공 → 세션 시작 → 캐시 비움. 개인화 결과가 이전 상태로 남지 않는다 */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation<AuthTokens, ApiError, LoginForm>({
    mutationFn: login,
    onSuccess: (tokens) => {
      startSession(tokens);
      queryClient.clear();
    },
  });
}

export function useSignup() {
  return useMutation<null, ApiError, SignupForm>({ mutationFn: signup });
}

/** logout() 호출 → 성공 · 실패 무관 세션 비움 → 캐시 비움 */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation<null, ApiError, void>({
    mutationFn: logout,
    onSettled: () => {
      endSession();
      queryClient.clear();
    },
  });
}
