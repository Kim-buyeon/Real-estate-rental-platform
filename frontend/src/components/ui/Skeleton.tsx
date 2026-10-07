import hidden from './visuallyHidden.module.css';
import styles from './Skeleton.module.css';

/** 화면 낭독기가 읽는 기본 문구 — Spinner 와 같다 */
const DEFAULT_LABEL = '불러오는 중';

interface SkeletonProps {
  /** 자리 표시 막대의 수. 목록이면 한 쪽에 보일 행 수 정도, 카드 그리드면 카드 수 */
  count?: number;
  /** 묶음의 배치. 주지 않으면 세로로 쌓는다. 카드 그리드는 그 화면의 그리드 클래스를 그대로 준다 */
  className?: string;
  /** 막대 하나의 크기. 주지 않으면 전폭 · 한 줄 높이다 */
  itemClassName?: string;
  /** 숨김 글자. 보이지 않고 화면 낭독기만 읽는다 */
  label?: string;
}

/**
 * 디자인 토큰 정의서 7절의 `{components.skeleton}` — 목록 · 카드 자리 표시. 한 영역의 대기는 `Spinner` 다.
 *
 * 접근성: 묶음 전체가 role="status" 하나이고 숨김 글자 「불러오는 중」을 담는다. 막대는 장식이라 숨긴다 —
 * 막대마다 읽히면 같은 말이 count 번 나온다.
 * 도메인을 모른다 — 막대의 수와 크기만 받는다.
 */
export function Skeleton({ count = 1, className, itemClassName, label = DEFAULT_LABEL }: SkeletonProps) {
  const groupClasses = className ?? styles.group;
  const itemClasses = itemClassName ? `${styles.item} ${itemClassName}` : styles.item;
  return (
    <div className={groupClasses} role="status">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={itemClasses} aria-hidden="true" />
      ))}
      <span className={hidden.visuallyHidden}>{label}</span>
    </div>
  );
}
