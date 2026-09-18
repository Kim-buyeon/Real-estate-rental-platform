import type { ReactNode } from 'react';
import styles from './EmptyState.module.css';

interface EmptyStateProps {
  /** 1줄 — 무엇이 비었는지. `{colors.text}` */
  title: ReactNode;
  /** 2줄 — 무엇을 하면 채워지는지. `{colors.text-secondary}` */
  description: ReactNode;
}

/**
 * 디자인 토큰 정의서 7절의 `{components.empty-state}` — 2줄 중앙 정렬의 빈 목록 안내.
 *
 * **빈 목록은 조회 실패가 아니다.** 오류(`Alert variant="error"`)와 구분해서 쓴다.
 * 위아래 여백까지 이 컴포넌트가 갖는다 — 쓰는 화면이 미디어 쿼리를 다시 쓰지 않는다.
 *
 * 도메인을 모른다 — 두 줄의 문구만 받는다.
 */
export function EmptyState({ title, description }: EmptyStateProps) {
  return (
    <p className={styles.emptyState}>
      <span className={`${styles.title} type-body-lg`}>{title}</span>
      <span className={`${styles.description} type-body-lg`}>{description}</span>
    </p>
  );
}
