import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';

// 기본값은 여기 한 곳이다. 쿼리별 예외는 queries/<도메인>.ts의 그 정의 안에만 적는다 — 이슈 #81 계획 「정한 것」

const STALE_TIME_MS = 30_000;
const MAX_RETRY = 1;

/** 4xx는 다시 보내도 결과가 같다 — 재분석 429를 거듭 치지 않는다. 그 외(5xx · NETWORK_ERROR)는 한 번 */
function shouldRetry(failureCount: number, error: unknown): boolean {
  const isClientError =
    error instanceof ApiError && error.status !== null && error.status >= 400 && error.status < 500;
  return !isClientError && failureCount < MAX_RETRY;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: STALE_TIME_MS,
      retry: shouldRetry,
      refetchOnWindowFocus: false,
    },
  },
});
