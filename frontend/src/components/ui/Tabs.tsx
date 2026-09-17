import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import styles from './Tabs.module.css';

/*
 * 탭 목록과 선택된 탭의 패널. 공용 UI의 후보 어휘(frontend/CLAUDE.md 공용 UI)에 있는 `Tabs`다.
 *
 * 도메인을 모른다 — 탭의 의미(목록 · 상세)는 항목의 id와 문구로만 들어온다.
 * 데이터를 부르지 않고, 선택 상태도 갖지 않는다: 어느 탭이 선택되었는지는 밖(부모)이 소유한다.
 * 선택이 화면의 다른 상태(고른 매물)와 함께 움직이므로 여기에 두면 두 곳이 같은 것을 정하게 된다.
 *
 * 선택된 탭의 패널만 렌더한다 — 보이지 않는 탭의 자식이 쿼리를 부르지 않게 하는 것도 부모의 선택이다.
 */

export interface TabItem {
  /** 부모가 선택 상태로 쓰는 값. 이 컴포넌트는 의미를 모른다 */
  id: string;
  label: ReactNode;
  /** 아직 고를 수 없는 탭. 키보드 이동에서도 건너뛴다 */
  isDisabled?: boolean;
}

interface TabsProps {
  /** 탭 목록의 이름 — role="tablist"의 aria-label */
  label: string;
  items: readonly TabItem[];
  selectedId: string;
  onSelect: (id: string) => void;
  /** 선택된 탭의 패널 내용 */
  children: ReactNode;
}

export function Tabs({ label, items, selectedId, onSelect, children }: TabsProps) {
  const baseId = useId();
  const panelId = `${baseId}-panel`;
  const tabId = (id: string) => `${baseId}-tab-${id}`;
  // 좌우 이동으로 옮긴 탭에 포커스를 넘긴다 — 선택만 바꾸면 포커스가 이전 탭에 남는다
  const buttonRefs = useRef(new Map<string, HTMLButtonElement>());

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (step === 0) return;

    const selectable = items.filter((item) => !item.isDisabled);
    if (selectable.length === 0) return;
    const current = selectable.findIndex((item) => item.id === selectedId);
    const next = selectable[(current + step + selectable.length) % selectable.length];
    if (!next) return;

    event.preventDefault();
    onSelect(next.id);
    buttonRefs.current.get(next.id)?.focus();
  };

  return (
    <div className={styles.tabs}>
      <div role="tablist" aria-label={label} className={styles.list} onKeyDown={handleKeyDown}>
        {items.map((item) => {
          const isSelected = item.id === selectedId;
          return (
            <button
              key={item.id}
              ref={(node) => {
                if (node) buttonRefs.current.set(item.id, node);
                else buttonRefs.current.delete(item.id);
              }}
              type="button"
              role="tab"
              id={tabId(item.id)}
              aria-selected={isSelected}
              aria-controls={isSelected ? panelId : undefined}
              // 선택된 탭 하나만 Tab 키 순서에 둔다. 나머지는 좌우 화살표로 옮긴다 (roving tabindex)
              tabIndex={isSelected ? 0 : -1}
              disabled={item.isDisabled}
              className={isSelected ? `${styles.tab} ${styles.selected} type-label` : `${styles.tab} type-label`}
              onClick={() => onSelect(item.id)}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      <div role="tabpanel" id={panelId} aria-labelledby={tabId(selectedId)} className={styles.panel}>
        {children}
      </div>
    </div>
  );
}
