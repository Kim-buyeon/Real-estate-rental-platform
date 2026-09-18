import type { ComponentPropsWithoutRef } from 'react';
import styles from './CardForm.module.css';

type CardFormProps = ComponentPropsWithoutRef<'div'>;

/**
 * 디자인 토큰 정의서 7절의 `{components.card-form}` — 540 고정 · 패딩 80 · 반경 0 · 1px `{colors.border}`.
 * 모바일에서 전폭 · 패딩 24 · 보더 제거로 붕괴한다. 그 붕괴는 이 컴포넌트가 갖는다.
 *
 * `{components.card}`(= `Card`)와는 보더 색 · 반경 · 패딩 · 폭이 모두 다른 별 항목이라 별개 컴포넌트다.
 * 도메인을 모르고 데이터를 부르지 않는다 — 폼 카드의 면과 여백만 갖는다.
 */
export function CardForm({ className, ...rest }: CardFormProps) {
  const classes = className ? `${styles.cardForm} ${className}` : styles.cardForm;
  return <div className={classes} {...rest} />;
}
