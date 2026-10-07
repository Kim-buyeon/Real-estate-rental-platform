import type { ComponentPropsWithoutRef } from 'react';
import styles from './CardContent.module.css';

interface CardContentProps extends Omit<ComponentPropsWithoutRef<'button'>, 'children' | 'title'> {
  /** 위 작은 라벨 — 밑에 짧은 밑줄이 붙는다 */
  label: string;
  /** 2~3줄 제목 */
  heading: string;
}

/**
 * 디자인 토큰 정의서 7절 「델타 1007 — 카드 변형 셋」의 {components.card-content} — 메인 추천 콘텐츠 카드.
 *
 * 카드 전체가 버튼 하나다 — 카드 안에 다른 동작이 없다. 좌하단 원형 버튼은 관측 모양이고 따로 누르는 것이
 * 아니라 장식이다(누르는 영역은 카드 전체). 네이티브 button 의 props(onClick · aria-*)를 그대로 통과시킨다.
 * 도메인을 모른다 — 문구만 받는다.
 */
export function CardContent({ label, heading, className, type = 'button', ...rest }: CardContentProps) {
  const classes = className ? `${styles.card} ${className}` : styles.card;
  return (
    <button type={type} className={classes} {...rest}>
      <span className={`${styles.label} type-caption`}>{label}</span>
      <span className={`${styles.heading} type-content-title`}>{heading}</span>
      <span className={styles.circle} aria-hidden="true">
        <svg className={styles.chevron} viewBox="0 0 16 16">
          <polyline points="6,3 11,8 6,13" fill="none" stroke="currentColor" strokeWidth="1.5" />
        </svg>
      </span>
    </button>
  );
}
