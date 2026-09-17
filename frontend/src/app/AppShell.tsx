import { Link, Outlet } from 'react-router';
import { Button, buttonClassName } from '../components/ui';
import { useLogout } from '../queries/user';
import { useSession } from '../session/useSession';
import styles from './AppShell.module.css';

/** 공통 레이아웃 — 헤더 + 본문. 레이아웃 맵이 생기면 그 섹션 순서 · 그리드를 따른다 */
export function AppShell() {
  const { isAuthenticated } = useSession();
  const logoutMutation = useLogout();

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <Link to="/" className={`${styles.brand} type-heading-3`}>
          전월세 부동산 금융 플랫폼
        </Link>
        {/* 알림 진입은 NOTI-05 슬라이스가 채운다 */}
        <nav className={styles.actions} aria-label="계정">
          {isAuthenticated ? (
            <>
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
