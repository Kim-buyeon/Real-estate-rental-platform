import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { Alert, Badge, Button, Card } from '../../../components/ui';
import { notificationTypeLabel, notificationValueLabel } from '../../../domain/notification';
import { formatCount, formatDateTime } from '../../../lib/format';
import {
  notificationQueries,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
} from '../../../queries/notification';
import styles from './NotificationList.module.css';

/**
 * 알림 목록 (NOTI-05). 데이터를 부르는 컴포넌트다.
 *
 * 커서 목록이라 「더 보기」로 다음 쪽을 잇는다 — 전체 건수는 주지 않는다 (공통 규약 1.4).
 * 서버가 최신 알림 먼저 주므로 화면에서 다시 정렬하지 않는다 (명세 1.3).
 *
 * 읽은 알림도 함께 오므로 읽음 여부를 배경과 배지로 가른다. 읽음 처리는 수신 시점이 아니라
 * 사용자가 확인한 시점에 한다 — 항목의 「읽음」 버튼과 위의 「전체 읽음」이 그 시점이다
 * (알림 전달 문서 1.2).
 *
 * 비로그인 분기는 없다 — 이 화면은 RequireAuth 아래다.
 */
export function NotificationList() {
  const notificationsQuery = useInfiniteQuery(notificationQueries.list());
  // 훅 하나를 목록 전체가 나눠 쓴다 — 지금 처리 중인 것이 어느 항목인지는 변수(notificationId)로 안다
  const markReadMutation = useMarkNotificationRead();
  const markAllMutation = useMarkAllNotificationsRead();

  // 쪽마다 나뉜 항목을 한 배열로 잇는다. 렌더마다 다시 만들지 않는다
  const items = useMemo(
    () => notificationsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [notificationsQuery.data],
  );

  // 읽지 않은 수는 응답 값을 그대로 쓴다 — 페이지와 무관한 이 사용자의 전체 수이므로 목록에서 세지
  // 않는다 (명세 1.3). 「더 보기」로 뒤쪽을 더 받았으면 마지막으로 받아 온 쪽의 값이 가장 최근이다
  const unreadCount = notificationsQuery.data?.pages.at(-1)?.unreadCount ?? 0;

  if (notificationsQuery.isPending) {
    return <p className="type-body">불러오는 중입니다.</p>;
  }

  if (notificationsQuery.error) {
    return <Alert variant="error">{notificationsQuery.error.message}</Alert>;
  }

  if (items.length === 0) {
    return <Alert variant="info">받은 알림이 없습니다. 관심 매물을 등록하면 등급 변동과 등기 변동을 알려 드립니다.</Alert>;
  }

  return (
    <>
      <div className={styles.summary}>
        <p className="type-body">
          읽지 않은 알림 <strong>{formatCount(unreadCount)}</strong>건
        </p>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={unreadCount === 0}
          isLoading={markAllMutation.isPending}
          onClick={() => markAllMutation.mutate()}
        >
          전체 읽음
        </Button>
      </div>

      {/* 실패 문구는 서버 error.message 그대로다 */}
      {markAllMutation.error && <Alert variant="error">{markAllMutation.error.message}</Alert>}

      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.notificationId}>
            <Card className={`${styles.item} ${item.isRead ? styles.read : styles.unread}`}>
              <div className={styles.head}>
                <span className="type-body-strong">{item.title}</span>
                <Badge variant={item.isRead ? 'neutral' : 'primary'}>
                  {item.isRead ? '읽음' : '읽지 않음'}
                </Badge>
              </div>

              <dl className={`${styles.facts} type-caption`}>
                <div className={styles.fact}>
                  <dt>유형</dt>
                  <dd>{notificationTypeLabel(item.type)}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>매물 번호</dt>
                  <dd>{item.propertyId}</dd>
                </div>
                <div className={styles.fact}>
                  <dt>변동</dt>
                  {/* 값의 의미가 유형마다 달라 표기는 domain/notification.ts가 갈라 준다 */}
                  <dd>
                    {notificationValueLabel(item.type, item.beforeValue)} →{' '}
                    {notificationValueLabel(item.type, item.afterValue)}
                  </dd>
                </div>
                <div className={styles.fact}>
                  <dt>받은 시각</dt>
                  <dd>{formatDateTime(item.createdAt)}</dd>
                </div>
              </dl>

              {/* 실패한 항목에만 붙인다 */}
              {markReadMutation.error && markReadMutation.variables === item.notificationId && (
                <Alert variant="error">{markReadMutation.error.message}</Alert>
              )}

              {!item.isRead && (
                <div className={styles.actions}>
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    onClick={() => markReadMutation.mutate(item.notificationId)}
                    isLoading={markReadMutation.isPending && markReadMutation.variables === item.notificationId}
                  >
                    읽음
                  </Button>
                </div>
              )}
            </Card>
          </li>
        ))}
      </ul>

      {notificationsQuery.hasNextPage && (
        <Button
          type="button"
          variant="secondary"
          onClick={() => void notificationsQuery.fetchNextPage()}
          isLoading={notificationsQuery.isFetchingNextPage}
        >
          더 보기
        </Button>
      )}
    </>
  );
}
