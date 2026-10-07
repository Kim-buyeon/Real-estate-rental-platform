import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import styles from './PromoPanel.module.css';

interface PromoPanelTileProps extends Omit<ComponentPropsWithoutRef<'button'>, 'children'> {
  /** 위 아이콘(24). 모양은 정의서가 다루지 않는다(6절 Icon) — 쓰는 화면이 넣는다 */
  icon: ReactNode;
  label: string;
}

/**
 * 디자인 토큰 정의서 7절의 {components.promo-panel-tile} — {components.promo-panel} 안 흰 타일 버튼.
 * 네이티브 button 의 props 를 그대로 통과시킨다. 도메인을 모른다.
 */
export function PromoPanelTile({ icon, label, className, type = 'button', ...rest }: PromoPanelTileProps) {
  const classes = className ? `${styles.tile} ${className}` : styles.tile;
  return (
    <button type={type} className={classes} {...rest}>
      <span className={styles.tileIcon} aria-hidden="true">
        {icon}
      </span>
      <span className="type-caption">{label}</span>
    </button>
  );
}
