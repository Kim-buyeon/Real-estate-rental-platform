import hidden from './visuallyHidden.module.css';
import styles from './Spinner.module.css';

/** 화면 낭독기가 읽는 기본 문구. 무엇을 기다리는지 더 말해야 하는 화면만 label 로 바꾼다 */
const DEFAULT_LABEL = '불러오는 중';

interface SpinnerProps {
  /** 숨김 글자. 보이지 않고 화면 낭독기만 읽는다 */
  label?: string;
  className?: string;
}

/**
 * 디자인 토큰 정의서 7절의 `{components.spinner}` — 한 영역(패널 · 폼 · 패널 안 구역)의 짧은 대기.
 * 목록 · 카드 자리는 `Skeleton` 이다.
 *
 * 접근성: role="status" 영역 안에 숨김 글자 「불러오는 중」을 둔다. 고리 자체는 장식이라 숨긴다.
 * 도메인을 모른다 — 무엇을 불러오는지는 쓰는 화면이 label 로 말한다.
 */
export function Spinner({ label = DEFAULT_LABEL, className }: SpinnerProps) {
  const classes = className ? `${styles.spinner} ${className}` : styles.spinner;
  return (
    <div className={classes} role="status">
      <span className={styles.ring} aria-hidden="true" />
      <span className={hidden.visuallyHidden}>{label}</span>
    </div>
  );
}
