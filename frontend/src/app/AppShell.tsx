import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, NavLink, Outlet, useLocation, useMatches } from 'react-router';
import { Badge, Button, buttonClassName } from '../components/ui';
import { formatCount } from '../lib/format';
import { loginPath } from '../lib/routes';
import { notificationQueries } from '../queries/notification';
import { useLogout } from '../queries/user';
import { useSession } from '../session/useSession';
import { AppFooter } from './AppFooter';
import { NotificationStream } from './NotificationStream';
import { fillsViewport, hidesFooter, isAuthEntryRoute } from './routeHandle';
import styles from './AppShell.module.css';

/**
 * 내비 항목의 클래스. 활성 항목은 디자인 토큰 정의서 7절 {components.top-nav}가 정한 대로
 * primary 글자 + primary-surface pill이다 — 표시는 NavLink의 isActive 하나로 한다.
 */
const navLinkClassName = ({ isActive }: { isActive: boolean }) =>
  [styles.navLink, 'type-body', isActive ? styles.navLinkActive : null].filter(Boolean).join(' ');

/** 공통 레이아웃 — 헤더 + 본문 + 푸터. 섹션 순서 · 여백은 레이아웃 맵을 따른다 */
export function AppShell() {
  const { isAuthenticated } = useSession();
  const logoutMutation = useLogout();

  // 푸터를 붙일지는 라우트 표의 handle이 정한다 — 경로 문자열 비교를 여기 두지 않는다
  const matches = useMatches();
  const isFooterHidden = matches.some((match) => hidesFooter(match.handle));
  // 화면 높이 고정도 handle이 정한다. 고정하지 않으면 긴 목록이 셸 · 지도 높이를 늘린다 (이슈 158)
  const isViewportFilled = matches.some((match) => fillsViewport(match.handle));

  // 헤더 「로그인」은 지금 위치를 redirect로 싣는다 — 로그인 뒤 보던 화면으로 돌아온다 (이슈 140).
  // 로그인 · 가입 화면 자신은 싣지 않는다: 로그인 뒤 다시 로그인 화면으로 오는 루프가 된다
  const location = useLocation();
  const isOnAuthEntry = matches.some((match) => isAuthEntryRoute(match.handle));
  const loginTo = isOnAuthEntry ? '/login' : loginPath(`${location.pathname}${location.search}`);

  // 헤더의 읽지 않은 수. 알림 목록과 같은 쿼리 정의를 써 요청과 캐시가 하나다 — 읽지 않은 수만 주는
  // 엔드포인트가 없고, 목록 응답의 unreadCount가 페이지와 무관한 전체 수다 (알림 명세 1.3).
  // 비로그인 상태에서는 부르지 않는다 — 인증 필수 엔드포인트다
  const notificationsQuery = useInfiniteQuery({ ...notificationQueries.list(), enabled: isAuthenticated });
  const unreadCount = notificationsQuery.data?.pages.at(-1)?.unreadCount ?? 0;

  return (
    <div className={isViewportFilled ? `${styles.shell} ${styles.shellFilled}` : styles.shell}>
      {/* 실시간 수신(NOTI-03) 연결은 애플리케이션 전역에 하나다. 화면을 그리지 않는다 */}
      {isAuthenticated && <NotificationStream />}
      <header className={styles.header}>
        {/* 내용은 푸터와 같은 컨테이너 안에 둔다 — 넓은 화면에서 좌우 끝이 맞아야 한다 */}
        <div className={styles.container}>
          <Link to="/" className={`${styles.brand} type-heading-3`}>
            전월세 부동산 금융 플랫폼
          </Link>
          {/* 모바일은 이 줄이 가로로 스크롤된다 — 브랜드와 알림 배지가 먼저 보이고 나머지는 밀어서 본다 */}
          <div className={styles.rail}>
            <nav className={styles.nav} aria-label="주요 메뉴">
              {/* 지도는 비로그인에도 열리는 화면이라 로그인 여부와 무관하게 둔다 (인증 「선택」) */}
              <NavLink to="/map" className={navLinkClassName}>
                지도
              </NavLink>
              {isAuthenticated && (
                <>
                  <NavLink to="/notifications" className={navLinkClassName}>
                    알림
                    {unreadCount > 0 && (
                      <Badge variant="primary" aria-label={`읽지 않은 알림 ${formatCount(unreadCount)}건`}>
                        {formatCount(unreadCount)}
                      </Badge>
                    )}
                  </NavLink>
                  <NavLink to="/me/notification-subscriptions" className={navLinkClassName}>
                    알림 설정
                  </NavLink>
                  <NavLink to="/me/wishlist" className={navLinkClassName}>
                    관심 매물
                  </NavLink>
                  <NavLink to="/me/profile" className={navLinkClassName}>
                    계정 · 자격 정보
                  </NavLink>
                </>
              )}
            </nav>
            {/* 계정 액션. 내비와는 간격 + 구분선으로만 가른다 */}
            <div className={styles.account}>
              {isAuthenticated ? (
                <Button
                  variant="ghost"
                  size="sm"
                  isLoading={logoutMutation.isPending}
                  onClick={() => logoutMutation.mutate()}
                >
                  로그아웃
                </Button>
              ) : (
                <>
                  <Link to={loginTo} className={buttonClassName('ghost', 'sm')}>
                    로그인
                  </Link>
                  <Link to="/signup" className={buttonClassName('primary', 'sm')}>
                    회원가입
                  </Link>
                </>
              )}
            </div>
          </div>
        </div>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
      {!isFooterHidden && <AppFooter />}
    </div>
  );
}
