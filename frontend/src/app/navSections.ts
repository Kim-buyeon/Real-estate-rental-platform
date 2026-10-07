/**
 * 상단 메뉴의 묶음 — 메뉴 항목 하나가 화면 여럿을 탭으로 묶는다(이슈 489 · 레이아웃 맵 favorites · my-info).
 * 경로는 그대로 두고(딥링크 · 알림 링크) 묶음만 여기서 정한다. 헤더 메뉴(AppShell)와 묶음 머리(SectionLayout)가
 * 같은 표를 읽는다 — 메뉴 이름 = 묶음 제목이고, 메뉴가 가리키는 곳은 첫 탭이다.
 *
 * 어느 라우트가 어느 묶음인지는 라우트 표의 handle(`navSection`)이 정한다 — 경로 문자열 비교를 두지 않는다.
 */
export type NavSection = 'favorites' | 'myInfo';

export interface NavSectionTab {
  to: string;
  label: string;
}

export interface NavSectionDefinition {
  /** 헤더 메뉴 이름이자 묶음의 페이지 제목 */
  title: string;
  /** 첫 탭이 메뉴의 이동 대상이다 */
  tabs: readonly [NavSectionTab, ...NavSectionTab[]];
}

export const NAV_SECTIONS: Record<NavSection, NavSectionDefinition> = {
  favorites: {
    title: '관심 목록',
    tabs: [
      { to: '/me/wishlist', label: '관심 매물' },
      { to: '/notifications', label: '알림' },
    ],
  },
  myInfo: {
    title: '내 정보',
    tabs: [
      { to: '/me/profile', label: '계정' },
      { to: '/me/notification-subscriptions', label: '알림 설정' },
    ],
  },
};
