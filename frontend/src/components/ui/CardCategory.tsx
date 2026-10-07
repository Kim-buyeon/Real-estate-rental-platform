import type { ReactNode } from 'react';
import styles from './CardCategory.module.css';

interface CardCategoryProps {
  title: string;
  description: ReactNode;
  /** 우상단 배지 자리 — 정의서 「우상단 배지 자리」. Badge 를 넘긴다 */
  badge?: ReactNode;
  /** 우하단 자리 — 정의서 「우하단 ≈ 44 일러스트 자리」. 우리는 일러스트가 없어 쓰는 화면이 내용을 넣는다. 누를 수 없는 카드에서만 블록 내용(목록 등)을 넣는다 */
  footer?: ReactNode;
  /** 있으면 카드 전체가 버튼이다. 없으면 설명 카드(누를 수 없다) */
  onClick?: () => void;
}

/**
 * 디자인 토큰 정의서 7절 「델타 1007 — 카드 변형 셋」의 {components.card-category} — 메인 hero 아래 카테고리 카드.
 * 배치(1행 2장 · 2행 3장)는 쓰는 화면이 레이아웃 맵 home 을 따라 정한다. 높이는 그 배치의 행이 정한다.
 *
 * 누르는 카드는 `<button>` 하나로 감싼다 — 카드 안에 다른 동작이 없어 통째로 누른다. 그래서 안쪽 글자는
 * 제목 요소가 아니라 span 이다(버튼 안에는 구문 내용만 들어간다). 누를 수 없는 카드는 제목을 h2 로 둔다 —
 * 이 카드는 페이지 첫 구역(h1 바로 아래)에 놓이는 어휘다.
 * 도메인을 모른다 — 문구만 받는다.
 */
export function CardCategory({ title, description, badge, footer, onClick }: CardCategoryProps) {
  // 버튼 안에는 구문 내용만 들어가므로 안쪽 묶음은 span, 누를 수 없는 카드는 div 다
  const Part = onClick ? 'span' : 'div';
  const body = (
    <>
      <Part className={styles.head}>
        {onClick ? (
          <span className={`${styles.title} type-heading-3`}>{title}</span>
        ) : (
          <h2 className={`${styles.title} type-heading-3`}>{title}</h2>
        )}
        {badge}
      </Part>
      <Part className={`${styles.description} type-body-sm`}>{description}</Part>
      {footer && <Part className={styles.footer}>{footer}</Part>}
    </>
  );

  if (onClick) {
    return (
      <button type="button" className={`${styles.card} ${styles.interactive}`} onClick={onClick}>
        {body}
      </button>
    );
  }
  return <div className={styles.card}>{body}</div>;
}
