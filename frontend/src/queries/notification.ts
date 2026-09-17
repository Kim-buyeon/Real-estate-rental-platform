// 알림 쿼리 정의와 뮤테이션 훅. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
import {
  infiniteQueryOptions,
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import type { ApiError } from '../api/client';
import {
  fetchNotificationSubscriptions,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationSubscriptions,
  type NotificationSubscriptions,
} from '../api/notification';

export const notificationQueries = {
  /**
   * 도메인 루트. 무효화 연쇄(읽음 처리 · SSE 수신 · SSE 재연결 성공)가 이 아래 list 키로 걸리고,
   * 구독 설정 수정은 subscriptions 키로 걸린다 — 둘은 서로를 낡게 하지 않으므로 루트로 비우지 않는다.
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

  /**
   * NOTI-01 구독 설정. 요청을 바꾸는 값이 없어(로그인한 사용자 하나의 설정이다) 키에 더 들어갈 것이 없다.
   * 인증 필수라 로그인 · 로그아웃의 queryClient.clear()가 함께 비운다.
   */
  subscriptions: () =>
    queryOptions({
      queryKey: [...notificationQueries.all(), 'subscriptions'] as const,
      queryFn: fetchNotificationSubscriptions,
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

/**
 * NOTI-01 구독 설정 수정. 응답이 수정 뒤의 조회 결과와 같은 구조지만 캐시에 직접 넣지 않고 무효화한다 —
 * 저장된 값을 읽는 경로를 조회 하나로 둔다 (useUpdateProfile과 같은 이유).
 *
 * onSuccess가 무효화 프로미스를 **돌려준다** — v5는 onSuccess가 돌려준 프로미스를 기다린 뒤에야
 * 뮤테이션을 성공으로 바꾼다. 폼은 저장 성공에서 입력 초안을 비우는데(SubscriptionForm.tsx),
 * 재조회 전에 성공이 오면 옛 캐시 값이 한 번 보였다가 새 값으로 바뀐다.
 *
 * 낡는 것은 구독 설정뿐이다 — 무효화 연쇄 표의 「알림 구독 설정 수정 성공 | notification.subscriptions」.
 * wishlistMonitoring이 관심 매물 전체에 반영되지만(명세 1.2) 관심 매물 응답에 그 값이 실려 오지
 * 않으므로 wishlist는 낡지 않는다.
 */
export function useUpdateNotificationSubscriptions() {
  const queryClient = useQueryClient();
  return useMutation<NotificationSubscriptions, ApiError, NotificationSubscriptions>({
    mutationFn: updateNotificationSubscriptions,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: notificationQueries.subscriptions().queryKey }),
  });
}
