import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';
export type ButtonSize = 'sm' | 'md';

const TYPOGRAPHY_BY_SIZE: Record<ButtonSize, string> = {
  sm: 'type-caption',
  md: 'type-label',
};

/**
 * 버튼처럼 보이는 링크는 이 클래스를 라우터 Link에 입힌다. 두 번째 버튼 컴포넌트를 만들지 않는다.
 * Button.tsx와 파일을 나눈 것은 Fast Refresh 규칙(컴포넌트 파일은 컴포넌트만 내보낸다) 때문이다 — 밖에서는 index.ts로 함께 가져간다.
 */
export function buttonClassName(variant: ButtonVariant = 'primary', size: ButtonSize = 'md'): string {
  return [styles.button, styles[variant], styles[size], TYPOGRAPHY_BY_SIZE[size]].join(' ');
}
