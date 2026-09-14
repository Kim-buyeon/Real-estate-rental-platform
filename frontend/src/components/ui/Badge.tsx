import type { ComponentPropsWithoutRef } from 'react';
import styles from './Badge.module.css';

/**
 * 변형 이름. 정의서가 생기면 그 badge-* 항목 이름으로 맞춘다.
 * 도메인을 모른다 — 위험 등급 → 변형 매핑은 domain/risk.ts가 갖는다.
 */
export type BadgeVariant = 'neutral' | 'primary' | 'risk-safe' | 'risk-caution' | 'risk-danger' | 'risk-unanalyzed';

const CLASS_BY_VARIANT: Record<BadgeVariant, string | undefined> = {
  neutral: styles.neutral,
  primary: styles.primary,
  'risk-safe': styles.riskSafe,
  'risk-caution': styles.riskCaution,
  'risk-danger': styles.riskDanger,
  'risk-unanalyzed': styles.riskUnanalyzed,
};

interface BadgeProps extends ComponentPropsWithoutRef<'span'> {
  variant?: BadgeVariant;
}

export function Badge({ variant = 'neutral', className, ...rest }: BadgeProps) {
  const classes = [styles.badge, CLASS_BY_VARIANT[variant], 'type-caption', className].filter(Boolean).join(' ');
  return <span className={classes} {...rest} />;
}
