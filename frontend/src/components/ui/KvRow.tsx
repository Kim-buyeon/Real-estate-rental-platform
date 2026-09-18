import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import styles from './KvRow.module.css';

type KvRowListProps = ComponentPropsWithoutRef<'dl'>;

/**
 * 디자인 토큰 정의서 7절의 `{components.kv-row}` 묶음 — 라벨-값 행을 세로로 잇는 `<dl>`.
 *
 * 「행마다 하단 보더」는 행의 성질이라 `KvRow`가 갖고, 여기는 행을 세로로 잇는 것과 값 열의 색만
 * 갖는다. 위아래 여백은 쓰는 화면의 것이다 — 여기서 주지 않는다.
 */
export function KvRowList({ className, ...rest }: KvRowListProps) {
  const classes = className ? `${styles.list} type-body ${className}` : `${styles.list} type-body`;
  return <dl className={classes} {...rest} />;
}

interface KvRowProps {
  /** 라벨 열. `{typography.body-strong}` `{colors.text}`는 이 컴포넌트가 입힌다 */
  label: ReactNode;
  /** 값 열. 배지 · 링크 · 여러 줄이 그대로 들어간다 */
  children: ReactNode;
  /** 값 열에 더 입힐 클래스. 강조 · 경고 색처럼 쓰는 쪽의 사정이 여기로 온다 */
  valueClassName?: string;
}

/**
 * 디자인 토큰 정의서 7절의 `{components.kv-row}` 행 하나 — 라벨 열 150 · 높이 66 · 하단 1px
 * `{colors.border}`.
 *
 * **도메인을 모른다.** 라벨과 값만 받고, 무엇을 보여줄지는 쓰는 쪽이 정한다.
 * `KvRowList`(= `<dl>`) 안에 둔다 — `<dt>` · `<dd>`가 `<dl>` 밖에 있으면 안 된다.
 *
 * `<dl>` 구조가 아닌 곳(등기 이력의 `<li>` 목록)은 이 컴포넌트 대신 `kvRowClassName`을 쓴다
 * (kvRowClassName.ts).
 */
export function KvRow({ label, children, valueClassName }: KvRowProps) {
  const valueClasses = valueClassName ? `${styles.value} ${valueClassName}` : styles.value;
  return (
    <div className={styles.row}>
      <dt className={`${styles.label} type-body-strong`}>{label}</dt>
      <dd className={valueClasses}>{children}</dd>
    </div>
  );
}

