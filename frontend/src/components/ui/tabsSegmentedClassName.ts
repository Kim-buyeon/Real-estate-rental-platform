import styles from './TabsSegmented.module.css';

/**
 * {components.tabs-segmented} 의 칸 하나. 라우터 NavLink 의 className 함수에서 부른다 —
 * `className={({ isActive }) => tabsSegmentedClassName(isActive)}`. 선택(-selected)은 지금 위치다.
 *
 * TabsSegmented.tsx 와 파일을 나눈 것은 Fast Refresh 규칙(컴포넌트 파일은 컴포넌트만 내보낸다) 때문이다 —
 * `buttonClassName` 과 같은 구성이다.
 */
export function tabsSegmentedClassName(isSelected: boolean): string {
  return [styles.tab, isSelected ? styles.selected : null, 'type-body-lg'].filter(Boolean).join(' ');
}
