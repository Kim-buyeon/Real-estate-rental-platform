import type { ComponentProps } from 'react';
import styles from './Input.module.css';

type InputProps = ComponentProps<'input'>;

/** 오류 표시는 aria-invalid로 한다 — Field가 넘긴다 */
export function Input({ className, type = 'text', ...rest }: InputProps) {
  const classes = [styles.input, 'type-body', className].filter(Boolean).join(' ');
  return <input type={type} className={classes} {...rest} />;
}
