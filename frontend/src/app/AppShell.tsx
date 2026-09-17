import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, Outlet } from 'react-router';
import { Badge, Button, buttonClassName } from '../components/ui';
import { formatCount } from '../lib/format';
import { notificationQueries } from '../queries/notification';
import { useLogout } from '../queries/user';
import { useSession } from '../session/useSession';
import { NotificationStream } from './NotificationStream';
import styles from './AppShell.module.css';

/** 공통 레이아웃 — 헤더 + 본문. 레이아웃 맵이 생기면 그 섹션 순서 · 그리드를 따른다 */
export function AppShell() {
  const { isAuthenticated } = useSession();
  const logoutMutation = useLogout();

  // 헤더의 읽지 않은 수. 알림 목록과 같은 쿼리 정의를 써 요청과 캐시가 하나다 — 읽지 않은 수만 주는
  // 엔드포인트가 없고, 목록 응답의 unreadCount가 페이지와 무관한 전체 수다 (알림 명세 1.3).
  // 비로그인 상태에서는 부르지 않는다 — 인증 필수 엔드포인트다
  const notificationsQuery = useInfiniteQuery({ ...notificationQueries.list(), enabled: isAuthenticated });
  const unreadCount = notificationsQuery.data?.pages.at(-1)?.unreadCount ?? 0;

  return (
    <div className={styles.shell}>
      {/* 실시간 수신(NOTI-03) 연결은 애플리케이션 전역에 하나다. 화면을 그리지 않는다 */}
      {isAuthenticated && <NotificationStream />}
      <header className={styles.header}>
        <Link to="/" className={`${styles.brand} type-heading-3`}>
          전월세 부동산 금융 플랫폼
        </Link>
        <nav className={styles.actions} aria-label="계정">
          {isAuthenticated ? (
            <>
              <Link to="/notifications" className={buttonClassName('ghost', 'sm')}>
                알림
                {unreadCount > 0 && (
                  <Badge variant="primary" aria-label={`읽지 않은 알림 ${formatCount(unreadCount)}건`}>
                    {formatCount(unreadCount)}
                  </Badge>
                )}
              </Link>
              <Link to="/me/notification-subscriptions" className={buttonClassName('ghost', 'sm')}>
                알림 설정
              </Link>
              <Link to="/me/wishlist" className={buttonClassName('ghost', 'sm')}>
                관심 매물
              </Link>
              <Link to="/me/profile" className={buttonClassName('ghost', 'sm')}>
                계정 · 자격 정보
              </Link>
              <Button
                variant="ghost"
                size="sm"
                isLoading={logoutMutation.isPending}
                onClick={() => logoutMutation.mutate()}
              >
                로그아웃
              </Button>
            </>
          ) : (
            <>
              <Link to="/login" className={buttonClassName('ghost', 'sm')}>
                로그인
              </Link>
              <Link to="/signup" className={buttonClassName('primary', 'sm')}>
                회원가입
              </Link>
            </>
          )}
        </nav>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  );
}
