import type { ReactNode } from 'react';
import styles from './PromoPanel.module.css';

interface PromoPanelProps {
  title: string;
  description: string;
  /** 오른쪽 타일 — PromoPanelTile 을 넘긴다 */
  children: ReactNode;
}

/**
 * 디자인 토큰 정의서 7절 「델타 1007 — 고유 컴포넌트 둘」의 {components.promo-panel} — 메인 우 레일의 상담 입구.
 * {colors.primary} 를 큰 면에 쓰는 유일한 예외 자리다(정의서 8절 Don't · K-34) — 다른 화면 · 다른 자리에 쓰지 않는다.
 * 제목이 h2 인 것은 메인 첫 구역(h1 바로 아래)에 놓이기 때문이다. 도메인을 모른다 — 문구와 타일만 받는다.
 */
export function PromoPanel({ title, description, children }: PromoPanelProps) {
  return (
    <section className={styles.panel} aria-label={title}>
      <div className={styles.text}>
        <h2 className={`${styles.title} type-heading-3`}>{title}</h2>
        <p className={`${styles.description} type-body-sm`}>{description}</p>
      </div>
      <div className={styles.tiles}>{children}</div>
    </section>
  );
}
