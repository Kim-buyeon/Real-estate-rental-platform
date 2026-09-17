// 회원 쿼리 정의와 뮤테이션 훅. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { endSession, startSession, type ApiError, type AuthTokens } from '../api/client';
import {
  fetchProfile,
  login,
  logout,
  signup,
  updateProfile,
  type LoginForm,
  type Profile,
  type ProfileForm,
  type SignupForm,
} from '../api/user';
import { loanQueries } from './loan';

export const userQueries = {
  /** 도메인 루트. 무효화 연쇄(프로필 수정)가 이 아래의 키로 걸린다 */
  all: () => ['user'] as const,

  /** USER-03 계정 · 자격 정보 조회. 인증 필수라 로그인 · 로그아웃의 queryClient.clear()가 함께 비운다 */
  profile: () =>
    queryOptions({
      queryKey: [...userQueries.all(), 'profile'] as const,
      queryFn: fetchProfile,
    }),
};

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

/**
 * USER-03 계정 · 자격 정보 수정. 응답이 수정 뒤의 조회 결과와 같은 구조지만 캐시에 직접 넣지 않고
 * 무효화한다 — missingFields는 서버가 저장된 값을 보고 다시 정하므로 조회 한 경로로만 읽는다.
 *
 * onSuccess가 무효화 프로미스를 **돌려준다** — v5는 onSuccess가 돌려준 프로미스를 기다린 뒤에야
 * 뮤테이션을 성공으로 바꾸므로, 성공 시점이 「재조회된 값이 캐시에 들어온 시점」이 된다. 폼은 저장
 * 성공에서 입력 초안을 비우는데(ProfileForm.tsx), 재조회 전에 성공이 오면 옛 캐시 값이 한 번 보였다가
 * 새 값으로 바뀐다. 기다리는 쪽을 훅에 두어 화면이 queryClient를 직접 잡지 않게 한다.
 */
export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation<Profile, ApiError, ProfileForm>({
    mutationFn: updateProfile,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: userQueries.profile().queryKey }),
        // 무효화 연쇄 표는 여기에 loan 전체도 정한다 — 자격 정보가 대출 한도 계산의 입력이라
        // 소득 · 신용점수 · 기존 대출이 바뀌면 이전 한도가 낡는다. 매물마다 따로 잡힌 키를
        // 한꺼번에 비워야 하므로 limit(propertyId) 하나가 아니라 루트다
        queryClient.invalidateQueries({ queryKey: loanQueries.all() }),
      ]),
  });
}
