import { NavLink, Outlet, useMatches } from 'react-router';
import { TabsSegmented, tabsSegmentedClassName } from '../components/ui';
import { NAV_SECTIONS } from './navSections';
import { activeNavSection } from './routeHandle';
import styles from './SectionLayout.module.css';

/** 탭 칸의 클래스 — 선택은 지금 위치다(NavLink 의 isActive) */
const tabClassName = ({ isActive }: { isActive: boolean }) => tabsSegmentedClassName(isActive);

/**
 * 메뉴 묶음(관심 목록 · 내 정보)의 머리 — 페이지 제목 + {components.tabs-segmented} 링크 탭, 그 아래가 탭의 화면이다.
 * 레이아웃 맵 favorites · my-info 의 1 ~ 3(top-nav 다음 page-title 가운데 · 탭 스트립 전폭)이고, 4 이후(빈 상태 ·
 * 폼 카드)는 각 화면이 갖는다. 어느 묶음인지는 라우트 표의 handle 이 정한다 — 제목 · 탭은 navSections 표 하나다.
 *
 * 경로는 바꾸지 않았다 — 탭은 화면 안의 패널 전환이 아니라 경로 사이의 이동이다(딥링크 · 알림 링크가 그대로다).
 */
export function SectionLayout() {
  const matches = useMatches();
  const section = activeNavSection(matches.map((match) => match.handle));
  if (!section) return <Outlet />;

  const { title, tabs } = NAV_SECTIONS[section];
  return (
    <div className={styles.section}>
      <div className={styles.head}>
        <h1 className={`${styles.title} type-display type-display-mobile`}>{title}</h1>
        <TabsSegmented label={title}>
          {tabs.map((tab) => (
            <NavLink key={tab.to} to={tab.to} end className={tabClassName}>
              {tab.label}
            </NavLink>
          ))}
        </TabsSegmented>
      </div>
      <Outlet />
    </div>
  );
}
