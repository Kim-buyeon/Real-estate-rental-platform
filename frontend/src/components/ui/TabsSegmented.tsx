import type { ComponentPropsWithoutRef } from 'react';
import styles from './TabsSegmented.module.css';

/*
 * 디자인 토큰 정의서 7절 Containment 의 {components.tabs-segmented}(+ -selected) — 페이지 상단 탭 스트립.
 *
 * `Tabs`(underline · pill)와 컴포넌트를 나눈 이유 — 그쪽은 한 화면 안의 패널을 고르는 탭(role="tablist" ·
 * 선택 상태는 부모 · 패널 · 좌우 화살표 이동)이고, 이것은 **다른 화면으로 가는 이동**이다. 탭마다 경로가 있어
 * 링크이고, 선택 표시는 라우터가 정한다(지금 위치). 동작이 달라 Tabs 의 variant 로 두면 variant 가 역할(role)까지
 * 바꾸게 된다. 그래서 이동 묶음의 의미(<nav>)만 여기 두고, 칸은 라우터 링크에 `tabsSegmentedClassName` 을 입힌다 —
 * `buttonClassName` 을 Link 에 입히는 것과 같은 구성이다. 공용 UI 는 라우터를 모른다.
 *
 * 도메인을 모른다 — 탭의 의미는 넣는 쪽의 링크 문구로만 들어온다.
 */
interface TabsSegmentedProps extends ComponentPropsWithoutRef<'nav'> {
  /** 탭 묶음의 이름 — <nav> 의 aria-label. 헤더의 「주요 메뉴」와 구분된다 */
  label: string;
}

export function TabsSegmented({ label, className, children, ...rest }: TabsSegmentedProps) {
  return (
    <nav aria-label={label} className={[styles.strip, className].filter(Boolean).join(' ')} {...rest}>
      {children}
    </nav>
  );
}
