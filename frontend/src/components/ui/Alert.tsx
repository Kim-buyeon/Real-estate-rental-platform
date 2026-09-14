import type { ComponentPropsWithoutRef } from 'react';
import styles from './Alert.module.css';

/** info — 빈 상태 · 안내, error — 조회 실패. 뮤테이션 실패는 Toast다 */
export type AlertVariant = 'info' | 'error';

const CLASS_BY_VARIANT: Record<AlertVariant, string | undefined> = {
  info: styles.info,
  error: styles.error,
};

const ROLE_BY_VARIANT: Record<AlertVariant, 'alert' | 'status'> = {
  info: 'status',
  error: 'alert',
};

interface AlertProps extends ComponentPropsWithoutRef<'div'> {
  variant?: AlertVariant;
}

export function Alert({ variant = 'info', className, role, ...rest }: AlertProps) {
  const classes = [styles.alert, CLASS_BY_VARIANT[variant], 'type-body', className].filter(Boolean).join(' ');
  return <div role={role ?? ROLE_BY_VARIANT[variant]} className={classes} {...rest} />;
}
