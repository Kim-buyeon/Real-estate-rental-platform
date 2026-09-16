import type { ComponentPropsWithoutRef } from 'react';
import styles from './Select.module.css';

type SelectProps = ComponentPropsWithoutRef<'select'>;

/** 네이티브 select 하나. Field가 넘기는 control props를 그대로 펼쳐 받는다 */
export function Select({ className, ...rest }: SelectProps) {
  const classes = [styles.select, 'type-body', className].filter(Boolean).join(' ');
  return <select className={classes} {...rest} />;
}
