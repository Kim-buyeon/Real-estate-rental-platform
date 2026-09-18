import { useCallback, useMemo, useState } from 'react';
import type { PropertyFilter } from '../api/property';
import { Button, Tabs, type TabItem } from '../components/ui';
import {
  DistrictPicker,
  MapExplorer,
  PropertyDetailPanel,
  PropertyFilterBar,
  PropertyList,
  useMapStage,
} from '../features/property';
import styles from './MapPage.module.css';

const INITIAL_FILTER: PropertyFilter = {};

/** 지도 옆 패널의 탭. 목록(PROP-01)과 상세(PROP-03)가 같은 자리를 나눠 쓴다 */
const LIST_TAB = 'list';
const DETAIL_TAB = 'detail';
type PanelTab = typeof LIST_TAB | typeof DETAIL_TAB;

/**
 * 좁은 화면에서 지도와 패널 중 무엇을 보이는가. 데스크톱 · 태블릿에서는 둘 다 보이므로
 * 이 값이 배치를 바꾸지 않는다 — 미디어 쿼리 밖에서는 클래스가 아무것도 가리지 않는다.
 */
const MAP_VIEW = 'map';
const PANEL_VIEW = 'panel';
type NarrowView = typeof MAP_VIEW | typeof PANEL_VIEW;

/**
 * `/map` 지도 탐색. 필터 · 단계 · 고른 매물 · 패널 탭을 소유하고 기능 컴포넌트를 조합한다 —
 * 쿼리는 부르지 않는다.
 *
 * 목록은 별도 화면이 아니라 상세와 같은 패널 자리의 탭이다 (PROP-02 계획 승인) — 화면을 옮기지
 * 않으므로 지도 위치 · 확대 수준이 유지되고, 필터도 지도와 목록이 같은 상태 하나를 본다 (명세 1.1).
 */
export default function MapPage() {
  const [filter, setFilter] = useState<PropertyFilter>(INITIAL_FILTER);
  const [detailPropertyId, setDetailPropertyId] = useState<number | null>(null);
  const [panelTab, setPanelTab] = useState<PanelTab>(LIST_TAB);
  const [narrowView, setNarrowView] = useState<NarrowView>(MAP_VIEW);
  const { stage, selectDistrict, backToSeoul } = useMapStage();

  // 필터는 단계 · 탭과 분리해 둔다. 되돌아가거나 탭을 오가도 조건이 그대로다 (매물 API 명세 1.2)
  const handleChangeFilter = useCallback((next: PropertyFilter) => setFilter(next), []);

  const toggleNarrowView = useCallback(
    () => setNarrowView((view) => (view === MAP_VIEW ? PANEL_VIEW : MAP_VIEW)),
    [],
  );

  /**
   * 매물을 고르는 경로는 둘이고 결과는 같다 — 마커 미리보기의 「상세 보기」와 목록 항목이다.
   * 고른 매물을 바꾸고 상세 탭으로 옮긴다. 탭을 옮기지 않으면 상세가 열린 줄 모른다.
   *
   * 좁은 화면에서는 지도 위에서 고른 것이므로 패널 쪽으로 함께 넘긴다 — 상세가 목록과 같은 자리를
   * 전체 화면으로 덮는다. 넘기지 않으면 지도만 보이는 채로 상세가 열려 있는 줄 모른다.
   */
  const handleOpenDetail = useCallback((propertyId: number) => {
    setDetailPropertyId(propertyId);
    setPanelTab(DETAIL_TAB);
    setNarrowView(PANEL_VIEW);
  }, []);

  // 닫으면 상세 탭이 다시 비활성이 되므로 목록으로 되돌린다
  const handleCloseDetail = useCallback(() => {
    setDetailPropertyId(null);
    setPanelTab(LIST_TAB);
  }, []);

  const handleSelectTab = useCallback((id: string) => setPanelTab(id as PanelTab), []);

  // 고른 매물이 없으면 상세 탭에 보여줄 것이 없다 — 기본 탭은 목록이다
  const tabs = useMemo<TabItem[]>(
    () => [
      { id: LIST_TAB, label: '목록' },
      { id: DETAIL_TAB, label: '상세', isDisabled: detailPropertyId === null },
    ],
    [detailPropertyId],
  );

  const isMapShown = narrowView === MAP_VIEW;

  return (
    <section className={styles.page}>
      <PropertyFilterBar filter={filter} onChange={handleChangeFilter} />

      <div className={styles.stageBar}>
        {stage.type === 'district' && (
          <Button type="button" size="sm" variant="ghost" onClick={backToSeoul}>
            ← 서울 전체
          </Button>
        )}
        <DistrictPicker district={stage.type === 'district' ? stage.district : null} onSelect={selectDistrict} />
        <p className="type-caption">
          {stage.type === 'seoul'
            ? '지도의 자치구를 누르거나 위에서 골라 매물을 봅니다'
            : '지도를 움직이면 보이는 영역의 매물을 다시 조회합니다'}
        </p>
      </div>

      <div className={`${styles.explore} ${isMapShown ? styles.mapShown : styles.panelShown}`}>
        <div className={styles.mapArea}>
          <MapExplorer
            filter={filter}
            stage={stage}
            isShown={isMapShown}
            onSelectDistrict={selectDistrict}
            onOpenDetail={handleOpenDetail}
          />
        </div>

        <div className={styles.panel}>
          <Tabs label="매물 보기" items={tabs} selectedId={panelTab} onSelect={handleSelectTab}>
            {panelTab === LIST_TAB ? (
              <PropertyList filter={filter} onSelect={handleOpenDetail} />
            ) : (
              detailPropertyId !== null && (
                <PropertyDetailPanel propertyId={detailPropertyId} onClose={handleCloseDetail} />
              )
            )}
          </Tabs>
        </div>

        {/* 좁은 화면에서만 보이는 지도 ↔ 목록 전환. 새 컴포넌트를 만들지 않고 Button 의 기존 변형이다 */}
        <Button type="button" variant="secondary" className={styles.viewToggle} onClick={toggleNarrowView}>
          {isMapShown ? '목록' : '지도'}
        </Button>
      </div>
    </section>
  );
}
