import type { ComponentPropsWithoutRef } from 'react';
import styles from './Toast.module.css';

/** 정의서 7절 Feedback 의 toast · toast-error 다. 「default」가 `{components.toast}` 다 */
export type ToastVariant = 'default' | 'error';

const CLASS_BY_VARIANT: Record<ToastVariant, string | undefined> = {
  default: undefined,
  error: styles.error,
};

interface ToastProps extends ComponentPropsWithoutRef<'div'> {
  variant?: ToastVariant;
}

/**
 * 토스트 한 장의 모양. 언제 띄우고 언제 지우는지는 모른다 — ToastProvider 가 갖는다.
 * 도메인을 모른다 — 문구만 받는다.
 */
export function Toast({ variant = 'default', className, ...rest }: ToastProps) {
  const classes = [styles.toast, CLASS_BY_VARIANT[variant], 'type-body', className].filter(Boolean).join(' ');
  return <div className={classes} {...rest} />;
}
