// 알림 쿼리 정의와 뮤테이션 훅. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
import { infiniteQueryOptions, useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import type { ApiError } from '../api/client';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '../api/notification';

export const notificationQueries = {
  /**
   * 도메인 루트. 무효화 연쇄(읽음 처리 · SSE 수신 · SSE 재연결 성공)가 이 아래 list 키로 걸린다.
   * NOTI-01 구독 설정 쿼리(notification.subscriptions)가 다음 슬라이스에서 이 루트 아래 붙는다.
   */
  all: () => ['notification'] as const,

  /**
   * NOTI-05 알림 목록. 커서 목록이라 infiniteQueryOptions다 — 응답의 nextCursor를 그대로 다음
   * 요청에 넣는다 (공통 규약 1.4). 조건이 없어 키에 들어갈 값도 커서뿐이며, 커서는 pageParam으로
   * TanStack Query가 관리한다.
   *
   * 읽지 않은 수(unreadCount)도 이 응답에 실려 온다 — 별도 집계 엔드포인트가 없고, 목록을 무효화하면
   * 그 수도 함께 새로 온다. 헤더의 읽지 않은 수와 목록 화면이 같은 키를 공유해 요청이 한 번이다.
   */
  list: () =>
    infiniteQueryOptions({
      queryKey: [...notificationQueries.all(), 'list'] as const,
      queryFn: ({ pageParam }) => fetchNotifications(pageParam),
      initialPageParam: undefined as string | undefined, // v5는 필수
      getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.nextCursor : undefined),
    }),
};

/**
 * 무효화 연쇄 표의 「알림 읽음 처리 성공」 한 행이라 개별 · 전체 두 훅이 같은 함수를 쓴다.
 * 낡는 것은 목록뿐이다 — isRead와 unreadCount가 같은 응답에 있다.
 *
 * 무효화 프로미스를 돌려준다 — v5는 onSuccess가 돌려준 프로미스를 기다린 뒤에야 뮤테이션을 성공으로
 * 바꾸므로, 버튼의 로딩 표시가 「재조회된 isRead가 캐시에 들어올 때까지」 이어진다 (PROP-05와 같은 이유).
 */
const invalidateNotificationList = (queryClient: QueryClient) =>
  queryClient.invalidateQueries({ queryKey: notificationQueries.list().queryKey });

// 오류 타입은 ApiError다 — client가 어떤 실패든 ApiError로 바꿔 던지고, 화면은 error.message를
// 그대로 보여준다(404 NOTIFICATION_NOT_FOUND 포함). 코드별 문구를 프론트에 다시 적지 않는다.

/** NOTI-05 개별 읽음 처리. 변수는 notificationId다 — 사용자가 확인한 시점에 부른다 (알림 전달 문서 1.2) */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation<null, ApiError, number>({
    mutationFn: markNotificationRead,
    onSuccess: () => invalidateNotificationList(queryClient),
  });
}

/** NOTI-05 전체 읽음 처리 */
export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation<null, ApiError, void>({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => invalidateNotificationList(queryClient),
  });
}
