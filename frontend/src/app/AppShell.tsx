import { Link, Outlet } from 'react-router';
import styles from './AppShell.module.css';

/** 공통 레이아웃 — 헤더 + 본문. 레이아웃 맵이 생기면 그 섹션 순서 · 그리드를 따른다 */
export function AppShell() {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <Link to="/" className={`${styles.brand} type-heading-3`}>
          전월세 부동산 금융 플랫폼
        </Link>
        {/* 로그인 · 알림 진입은 USER-02 · NOTI-05 슬라이스가 채운다 */}
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  );
}
