import type { ReactNode } from 'react';
import styles from './Disclosure.module.css';

/*
 * 접기 · 펼치기 섹션.
 *
 * 공용 UI는 디자인 토큰 정의서의 컴포넌트 어휘와 1:1이고 정의서에 없는 것을 만들지 않는 것이
 * 규칙이지만(frontend/CLAUDE.md 공용 UI), 정의서는 아직 「미확정」 표에 있다. 이것은 이슈 #91의
 * 승인된 계획이 자리까지 지정한 컴포넌트이므로 예외로 둔다. 정의서가 반영되면 어휘와 대조한다.
 *
 * 열림 상태를 밖에서 받는다. 펼칠 때 비로소 조회를 시작해야 해서(매물 명세 1.4 「탐색 동작과
 * 호출 시점」) 부모가 열림 여부를 알아야 하기 때문이다 — 여닫는 주체가 부모이고, 그 상태가 곧
 * 자식을 마운트하는지 여부다. 조회 시점은 쿼리의 enabled가 아니라 이 마운트가 정한다.
 */

interface DisclosureProps {
  title: string;
  isOpen: boolean;
  onToggle: () => void;
  children: ReactNode;
}

export function Disclosure({ title, isOpen, onToggle, children }: DisclosureProps) {
  return (
    <section className={styles.disclosure}>
      <h3 className={styles.heading}>
        <button type="button" className={`${styles.trigger} type-body-strong`} onClick={onToggle} aria-expanded={isOpen}>
          <span>{title}</span>
          {/* 회전으로 방향을 바꾼다 — 열림 · 닫힘에 서로 다른 글자를 두지 않는다 */}
          <span className={isOpen ? `${styles.marker} ${styles.markerOpen}` : styles.marker} aria-hidden="true">
            ▸
          </span>
        </button>
      </h3>

      {/* 닫혀 있으면 자식을 마운트하지 않는다 — 자식이 부르는 쿼리가 열릴 때 시작한다 */}
      {isOpen && <div className={styles.body}>{children}</div>}
    </section>
  );
}
