import styles from './Button.module.css';

/**
 * 변형 이름은 디자인 토큰 정의서 7절 Actions 의 button-* 항목을 따른다.
 * 정의서의 나머지 변형(button-icon · button-social · pulldown)처럼 쓰는 화면이 생길 때 추가한다 —
 * 지금 화면이 쓰는 것만 둔다.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'footer-primary';
export type ButtonSize = 'sm' | 'md';

const CLASS_BY_VARIANT: Record<ButtonVariant, string | undefined> = {
  primary: styles.primary,
  secondary: styles.secondary,
  ghost: styles.ghost,
  'footer-primary': styles.footerPrimary,
};

/** 정의서 7절 — 주 버튼 라벨은 {typography.button}, 소형 버튼 라벨은 {typography.button-sm} 이다 */
const TYPOGRAPHY_BY_SIZE: Record<ButtonSize, string> = {
  sm: 'type-button-sm',
  md: 'type-button',
};

/**
 * 버튼처럼 보이는 링크는 이 클래스를 라우터 Link나 <a>에 입힌다. 두 번째 버튼 컴포넌트를 만들지 않는다.
 * Button.tsx와 파일을 나눈 것은 Fast Refresh 규칙(컴포넌트 파일은 컴포넌트만 내보낸다) 때문이다 — 밖에서는 index.ts로 함께 가져간다.
 */
export function buttonClassName(variant: ButtonVariant = 'primary', size: ButtonSize = 'md'): string {
  return [styles.button, CLASS_BY_VARIANT[variant], styles[size], TYPOGRAPHY_BY_SIZE[size]]
    .filter(Boolean)
    .join(' ');
}
