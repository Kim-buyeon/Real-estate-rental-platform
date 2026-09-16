import { useCallback, useState } from 'react';
import type { PropertyFilter } from '../api/property';
import { Button } from '../components/ui';
import { MapExplorer, PropertyFilterBar, useMapStage } from '../features/property';
import styles from './HomePage.module.css';

const INITIAL_FILTER: PropertyFilter = {};

/** `/` 지도 탐색. 필터와 단계 상태를 소유하고 기능 컴포넌트를 조합한다 — 쿼리는 부르지 않는다 */
export default function HomePage() {
  const [filter, setFilter] = useState<PropertyFilter>(INITIAL_FILTER);
  const { stage, selectDistrict, backToSeoul } = useMapStage();

  // 필터는 단계와 분리해 둔다. 되돌아가도 조건이 그대로다 (매물 API 명세 1.2)
  const handleChangeFilter = useCallback((next: PropertyFilter) => setFilter(next), []);

  return (
    <section className={styles.page}>
      <PropertyFilterBar filter={filter} onChange={handleChangeFilter} />

      <div className={styles.stageBar}>
        {stage.type === 'seoul' ? (
          <p className="type-label">자치구를 선택하면 매물 마커를 표시합니다.</p>
        ) : (
          <>
            <Button type="button" size="sm" variant="ghost" onClick={backToSeoul}>
              ← 서울 전체
            </Button>
            <p className="type-label">{stage.district}</p>
          </>
        )}
      </div>

      <MapExplorer filter={filter} stage={stage} onSelectDistrict={selectDistrict} />
    </section>
  );
}
