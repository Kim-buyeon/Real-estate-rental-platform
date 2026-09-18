import { useId, type ReactNode } from 'react';
import styles from './Field.module.css';

/** Field가 입력 요소에 넘기는 접근성 속성. 입력 요소에 그대로 펼친다 */
export interface FieldControlProps {
  id: string;
  'aria-invalid': boolean;
  'aria-describedby': string | undefined;
}

interface FieldProps {
  label: ReactNode;
  /** 오류 문구. 서버 error.message(field가 이 입력일 때)를 그대로 넘긴다 */
  error?: string | null;
  hint?: ReactNode;
  children: (control: FieldControlProps) => ReactNode;
}

export function Field({ label, error, hint, children }: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const hasError = Boolean(error);
  const describedBy = [hint ? hintId : null, hasError ? errorId : null].filter(Boolean).join(' ') || undefined;

  return (
    <div className={styles.field}>
      {/* 정의서 7절 {components.field} — 라벨은 {typography.body-strong} 이다 */}
      <label htmlFor={id} className={`${styles.label} type-body-strong`}>
        {label}
      </label>
      {children({ id, 'aria-invalid': hasError, 'aria-describedby': describedBy })}
      {hint && (
        <p id={hintId} className={`${styles.hint} type-caption`}>
          {hint}
        </p>
      )}
      {hasError && (
        <p id={errorId} className={`${styles.error} type-caption`} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
