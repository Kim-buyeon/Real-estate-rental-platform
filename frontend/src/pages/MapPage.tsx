import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import type { PropertyFilter } from '../api/property';
import { Alert, Button, Tabs, type TabItem } from '../components/ui';
import {
  DistrictPicker,
  MapExplorer,
  PropertyDetailPanel,
  PropertyFilterBar,
  PropertyList,
  useDetailTarget,
  useMapStage,
} from '../features/property';
import { readDetailPropertyId, withDetailPropertyId, withoutDetailPropertyId } from '../lib/routes';
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
 * `/map` 지도 탐색. 필터 · 단계 · 패널 탭을 소유하고 기능 컴포넌트를 조합한다 — 쿼리는 부르지 않는다.
 *
 * 목록은 별도 화면이 아니라 상세와 같은 패널 자리의 탭이다 (PROP-02 계획 승인) — 화면을 옮기지
 * 않으므로 지도 위치 · 확대 수준이 유지되고, 필터도 지도와 목록이 같은 상태 하나를 본다 (명세 1.1).
 *
 * **고른 매물만 URL에 있다** (`?propertyId=`, 이슈 104). 관심 매물 · 알림 · 메인의 최근 등록
 * 매물이 그 매물의 상세를 가리키려면 링크가 필요한데 상세가 화면이 아니라 이 화면의 패널이라
 * 검색 파라미터가 그 자리다. 필터 · 단계 · 탭 · 좁은 화면 전환은 그대로 로컬 상태다 — 지도를
 * 움직일 때마다 URL이 바뀌면 히스토리가 쌓인다.
 */
export default function MapPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  // 숫자가 아니거나 비어 있으면 없는 것으로 다룬다 — 판정은 lib/routes.ts가 갖는다
  const detailPropertyId = readDetailPropertyId(searchParams);

  const [filter, setFilter] = useState<PropertyFilter>(INITIAL_FILTER);
  /**
   * 사용자가 고른 탭. 화면이 실제로 보여주는 탭은 아래 panelTab이다 — 고른 매물이 없으면
   * 상세 탭에 보여줄 것이 없으므로 목록이 된다. 초기값이 상세인 것은 딥링크 때문이다:
   * `?propertyId=`를 달고 들어오면 곧바로 상세가 보여야 하고, 파라미터가 없는 평소 진입은
   * 아래 유도에서 목록이 된다.
   */
  const [selectedTab, setSelectedTab] = useState<PanelTab>(DETAIL_TAB);
  // 딥링크로 들어오면 패널 쪽을 보여준다 — 링크를 누른 목적이 그 매물이다 (이슈 104 계획)
  const [narrowView, setNarrowView] = useState<NarrowView>(detailPropertyId !== null ? PANEL_VIEW : MAP_VIEW);
  // 없는 매물 번호로 들어왔을 때의 서버 문구. 파라미터를 지우고 나면 조회가 사라지므로 여기가 들고 있는다
  const [missingMessage, setMissingMessage] = useState<string | null>(null);
  const { stage, selectDistrict, backToSeoul, showDistrict } = useMapStage();
  // 상세 응답에서 자치구와 404 여부만 읽는다. 패널과 같은 쿼리 정의라 요청은 하나다
  const { district: detailDistrict, notFoundMessage } = useDetailTarget(detailPropertyId);

  /**
   * 보여줄 탭. URL에서 매물이 사라지면(닫기 · 뒤로가기) 목록으로 되돌아간다 — 상태를 따로 맞추지
   * 않으므로 뒤로 · 앞으로가 서로 어긋나지 않는다.
   */
  const panelTab: PanelTab = detailPropertyId === null ? LIST_TAB : selectedTab;

  // 필터는 단계 · 탭 · URL과 분리해 둔다. 되돌아가거나 탭을 오가도 조건이 그대로다 (매물 API 명세 1.2)
  const handleChangeFilter = useCallback((next: PropertyFilter) => setFilter(next), []);

  const toggleNarrowView = useCallback(
    () => setNarrowView((view) => (view === MAP_VIEW ? PANEL_VIEW : MAP_VIEW)),
    [],
  );

  /**
   * 매물을 고르는 경로는 셋이고 결과는 같다 — 마커 미리보기의 「상세 보기」 · 목록 항목 · 다른
   * 화면에서 온 링크다. 고른 매물은 URL이 갖고 탭을 상세로 옮긴다. 탭을 옮기지 않으면 상세가
   * 열린 줄 모른다.
   *
   * 좁은 화면에서는 지도 위에서 고른 것이므로 패널 쪽으로 함께 넘긴다 — 상세가 목록과 같은 자리를
   * 전체 화면으로 덮는다. 넘기지 않으면 지도만 보이는 채로 상세가 열려 있는 줄 모른다.
   *
   * 히스토리에 쌓는다(push) — 뒤로가기가 이 열기를 하나 되돌린다 (이슈 104 계획).
   */
  const handleOpenDetail = useCallback(
    (propertyId: number) => {
      setSearchParams((prev) => withDetailPropertyId(prev, propertyId));
      setSelectedTab(DETAIL_TAB);
      setNarrowView(PANEL_VIEW);
      setMissingMessage(null);
    },
    [setSearchParams],
  );

  /**
   * 닫기도 히스토리에 쌓는다 — 뒤로가기가 방금 닫은 상세를 다시 연다. 탭은 건드리지 않는다:
   * 매물이 사라지면 위 유도가 목록을 보여주고, 뒤로가기로 매물이 돌아오면 상세가 그대로 열린다.
   */
  const handleCloseDetail = useCallback(() => {
    setSearchParams((prev) => withoutDetailPropertyId(prev));
  }, [setSearchParams]);

  const handleSelectTab = useCallback((id: string) => setSelectedTab(id as PanelTab), []);

  /**
   * 없는 매물 번호. 서버 문구를 그대로 알리고 파라미터를 지운다 — 링크가 잘못됐으므로 남겨 두면
   * 새로고침마다 같은 404가 되풀이된다. 지울 때만 replace다: 사용자의 동작이 아니라 잘못된 주소의
   * 정정이라 히스토리에 칸을 만들면 뒤로가기가 그 주소로 되돌아간다.
   *
   * 여기서 가르는 것은 404 하나다 — 네트워크 · 5xx는 패널이 제 자리에서 보여주고 파라미터를 남긴다
   * (features/property/hooks/useDetailTarget.ts).
   */
  const alertedPropertyId = useRef<number | null>(null);
  useEffect(() => {
    // 매물 번호 하나에 한 번만 알린다 — 파라미터를 지우면 그 번호를 다시 열 수 있게 기억을 비운다
    if (detailPropertyId === null) {
      alertedPropertyId.current = null;
      return;
    }
    if (notFoundMessage === null || alertedPropertyId.current === detailPropertyId) return;
    alertedPropertyId.current = detailPropertyId;
    setMissingMessage(notFoundMessage);
    setSearchParams((prev) => withoutDetailPropertyId(prev), { replace: true });
  }, [detailPropertyId, notFoundMessage, setSearchParams]);

  /**
   * 상세가 열린 매물의 자치구로 지도 단계를 맞춘다 — 새로고침 · 딥링크로 들어오면 패널만 열리고
   * 지도가 무관한 자리를 비추는 것을 막는다. 자치구는 상세 응답의 district이므로 추가 조회가 없다.
   *
   * 경계가 둘이다.
   * ① **매물 하나에 한 번만 따라간다**(followedPropertyId). 단계가 바뀔 때마다 따라가면 상세를
   *    열어 둔 채 「← 서울 전체」를 눌렀을 때 곧바로 자치구로 되돌려 버려 사용자와 다툰다.
   * ② **이미 그 자치구면 옮기지 않는다** — 판정은 useMapStage의 showDistrict가 한다. 그 구 안에서
   *    지도를 움직인 뒤 마커나 목록에서 상세를 열어도 중심이 튀지 않는다.
   */
  const followedPropertyId = useRef<number | null>(null);
  useEffect(() => {
    if (detailPropertyId === null) {
      followedPropertyId.current = null;
      return;
    }
    if (detailDistrict === null || followedPropertyId.current === detailPropertyId) return;
    followedPropertyId.current = detailPropertyId;
    showDistrict(detailDistrict);
  }, [detailDistrict, detailPropertyId, showDistrict]);

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

      {/* 없는 매물 번호로 들어왔을 때. 문구는 서버 error.message 그대로다 */}
      {missingMessage !== null && (
        <div className={styles.notice}>
          <Alert variant="error">{missingMessage}</Alert>
        </div>
      )}

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
