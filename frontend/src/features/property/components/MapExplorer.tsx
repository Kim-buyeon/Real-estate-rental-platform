import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import type { BoundingBox, PropertyFilter } from '../../../api/property';
import { Alert } from '../../../components/ui';
import { propertyQueries } from '../../../queries/property';
import {
  addClickListener,
  addIdleListener,
  createMap,
  createOverlayLayer,
  fitBoundingBox,
  fitSeoul,
  groupMarkers,
  isMapSdkReady,
  lockSeoulView,
  moveToPoint,
  OVERLAY_Z_FRONT,
  readBoundingBox,
  relayoutMap,
  searchDistrictPoint,
  type GroupedMarkers,
  type KakaoMap,
  type MarkerCluster,
  type OverlayLayer,
} from '../map';
import { useDistrictPoints } from '../hooks/useDistrictPoints';
import type { MapStage } from '../hooks/useMapStage';
import { DistrictOverlayContent } from './DistrictOverlayContent';
import { MarkerClusterContent } from './MarkerClusterContent';
import { MarkerPreviewCard } from './MarkerPreviewCard';
import { PropertyMarkerContent } from './PropertyMarkerContent';
import styles from './MapExplorer.module.css';

/** 아직 이번 단계의 표시 영역을 읽지 못했을 때 쿼리 정의에 넘기는 자리값. enabled가 false라 요청되지 않는다 */
const PENDING_BBOX: BoundingBox = { minLat: 0, maxLat: 0, minLng: 0, maxLng: 0 };

const stageKeyOf = (stage: MapStage) => (stage.type === 'seoul' ? 'seoul' : `district:${stage.district}`);

/**
 * 읽은 표시 영역과 그때의 단계. 단계를 함께 들고 있어야 자치구로 막 옮긴 순간에
 * 이전 단계(서울 전체)의 넓은 영역으로 마커를 조회하지 않는다.
 */
interface MapView {
  stageKey: string;
  bbox: BoundingBox;
}

interface OverlayItem {
  key: string;
  lat: number;
  lng: number;
  node: ReactNode;
  /** 겹칠 때 앞으로 올릴 것 */
  zIndex?: number;
}

interface MapExplorerProps {
  filter: PropertyFilter;
  stage: MapStage;
  /**
   * 지도가 화면에 보이는가. 좁은 화면의 지도 ↔ 목록 전환에서 감춰진 동안 컨테이너 크기가 0이
   * 되므로, 다시 보이는 시점에 relayout 해야 타일이 그려진다 (kakao-map 5장).
   * 넓은 화면에서도 거짓이 된다 — 목록에서 상세를 열면 HomePage가 setNarrowView(PANEL_VIEW)를 한다.
   * 그때 지도는 계속 보이지만 relayout이 멱등이라 다시 부르는 것이 무해하다.
   */
  isShown?: boolean;
  onSelectDistrict: (district: string) => void;
  onOpenDetail: (propertyId: number) => void;
}

/**
 * 지도 드릴다운 1 · 2단계. 오버레이 내용은 React 컴포넌트를 포털로 그리고,
 * CustomOverlay의 생성 · 제거는 map 폴더가 맡는다 (kakao-map 5장).
 */
export function MapExplorer({ filter, stage, isShown = true, onSelectDistrict, onOpenDetail }: MapExplorerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const layerRef = useRef<OverlayLayer | null>(null);
  // 포털 컨테이너. 키가 같으면 같은 엘리먼트를 다시 쓴다 — 오버레이는 새 것만 만들고 사라진 것만 지운다
  const [containers] = useState(() => new Map<string, HTMLDivElement>());

  // SDK는 index.html의 정적 <script>로 먼저 실행된다. 첫 렌더에 판정하면 효과에서 상태를 바꾸지 않아도 된다
  const [isSdkMissing] = useState(() => !isMapSdkReady());
  const [districtError, setDistrictError] = useState<string | null>(null);
  const [view, setView] = useState<MapView | null>(null);
  const [previewId, setPreviewId] = useState<number | null>(null);
  // 서울 전체에서 자치구 말풍선이 서로 가린다. 가리킨 것을 앞으로 올린다
  const [frontDistrict, setFrontDistrict] = useState<string | null>(null);

  const stageKey = stageKeyOf(stage);
  // idle 핸들러는 지도와 함께 한 번만 등록하므로 현재 단계를 ref로 읽는다
  const stageKeyRef = useRef(stageKey);
  useEffect(() => {
    stageKeyRef.current = stageKey;
  }, [stageKey]);

  // 지도 생성 · idle 구독. 표시 영역은 idle에서만 갱신한다 (kakao-map 4장)
  useEffect(() => {
    const container = containerRef.current;
    if (!container || isSdkMissing) return;

    const map = createMap(container);
    mapRef.current = map;
    layerRef.current = createOverlayLayer(map);
    setView({ stageKey: stageKeyRef.current, bbox: readBoundingBox(map) });

    const removeIdle = addIdleListener(map, () =>
      setView({ stageKey: stageKeyRef.current, bbox: readBoundingBox(map) }),
    );
    const removeClick = addClickListener(map, () => setPreviewId(null));

    // 컨테이너 크기가 바뀌면 타일이 어긋난다 (kakao-map 5장).
    // 상세 패널이 열리고 닫힐 때도 지도 폭이 바뀌므로 창이 아니라 컨테이너를 본다.
    // 크기가 0인 동안(좁은 화면에서 목록으로 전환해 감춰졌을 때)은 부르지 않는다 — 그 크기로
    // 맞춰 두면 다시 보일 때 중심과 확대 수준이 어긋난다. 돌아오는 시점의 호출은 아래 효과가 한다
    const handleResize = () => {
      if (container.clientWidth === 0 || container.clientHeight === 0) return;
      relayoutMap(map);
    };
    window.addEventListener('resize', handleResize);
    const observer = new ResizeObserver(handleResize);
    observer.observe(container);

    // 컨테이너 크기가 잡히기 전에 만들어졌으면 타일이 안 그려지고 확대 수준도 엉뚱하다.
    // 크기가 확정된 다음 프레임에 서울 전체로 다시 맞춘다
    const relayoutFrame = requestAnimationFrame(() => {
      lockSeoulView(map);
      setView({ stageKey: stageKeyRef.current, bbox: readBoundingBox(map) });
    });

    return () => {
      cancelAnimationFrame(relayoutFrame);
      observer.disconnect();
      window.removeEventListener('resize', handleResize);
      removeIdle();
      removeClick();
      layerRef.current?.clear();
      layerRef.current = null;
      mapRef.current = null;
      containers.clear();
    };
  }, [containers, isSdkMissing]);

  /**
   * 감춰져 있던 지도가 다시 보이는 시점의 relayout. 숨겨진 동안 컨테이너 크기가 0이었다가
   * 돌아오면 SDK가 스스로 타일을 다시 그리지 않는다 (kakao-map 5장 「지도 컨테이너 크기가
   * 바뀌면 relayout()」). SDK 호출은 map 폴더의 relayoutMap을 거친다 — 그 함수는 map.relayout()만
   * 부르고, window.kakao 전역을 읽는 곳은 map/map.ts의 isMapSdkReady · requireMaps 둘이다.
   */
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !isShown) return;
    relayoutMap(map);
  }, [isShown]);

  // 단계 이동. 자치구는 Geocoder가 돌려준 중심으로 옮기고, 그 뒤 첫 idle이 표시 영역을 읽는다.
  // idle이 오지 않는 경우를 대비해 이동 직후에도 한 번 읽는다 (kakao-map 6장)
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    setPreviewId(null);
    setFrontDistrict(null);
    setDistrictError(null);

    if (stage.type === 'seoul') {
      fitSeoul(map);
      setView({ stageKey: stageKeyOf(stage), bbox: readBoundingBox(map) });
      return;
    }

    let cancelled = false;
    searchDistrictPoint(stage.district)
      .then((point) => {
        const current = mapRef.current;
        if (cancelled || !current) return;
        moveToPoint(current, point.lat, point.lng);
        setView({ stageKey: stageKeyOf(stage), bbox: readBoundingBox(current) });
      })
      .catch(() => {
        if (!cancelled) setDistrictError('자치구 위치를 찾지 못했습니다. 지도를 움직여 조회해 주세요.');
      });

    return () => {
      cancelled = true;
    };
  }, [stage]);

  const isSeoul = stage.type === 'seoul';
  const districtCountsQuery = useQuery({ ...propertyQueries.districtCounts(filter), enabled: isSeoul });

  const markerFilter = useMemo(
    () => (stage.type === 'district' ? { ...filter, district: stage.district } : filter),
    [filter, stage],
  );
  // 이번 단계에서 읽은 표시 영역이 있을 때만 조회한다 — 단계가 바뀐 직후의 한 번을 막는다
  const stageBbox = view?.stageKey === stageKey ? view.bbox : null;
  const markersQuery = useQuery({
    ...propertyQueries.markers(markerFilter, stageBbox ?? PENDING_BBOX),
    enabled: !isSeoul && stageBbox !== null,
  });

  /** 건수 순위. 겹칠 때 어느 말풍선이 위로 갈지 정한다 — 건수를 그대로 쓰면 상한에 걸려 평평해진다 */
  const districtRank = useMemo(() => {
    const byCount = [...(districtCountsQuery.data?.districts ?? [])].sort((a, b) => a.count - b.count);
    return new Map(byCount.map((district, index) => [district.name, index]));
  }, [districtCountsQuery.data]);

  const districtNames = useMemo(
    () => (districtCountsQuery.data?.districts ?? []).map((district) => district.name),
    [districtCountsQuery.data],
  );
  const districtPoints = useDistrictPoints(isSeoul ? districtNames : []);

  const handleHoverMarker = useCallback((propertyId: number) => setPreviewId(propertyId), []);
  const handleSelectMarker = useCallback((propertyId: number) => setPreviewId(propertyId), []);
  const handleClosePreview = useCallback(() => setPreviewId(null), []);
  const handleHoverDistrict = useCallback((name: string) => setFrontDistrict(name), []);
  const handleSelectCluster = useCallback((cluster: MarkerCluster) => {
    const map = mapRef.current;
    if (!map) return;
    setPreviewId(null);
    fitBoundingBox(map, cluster.bbox);
  }, []);

  const markers = markersQuery.data?.items;
  const previewMarker = useMemo(
    () => (previewId === null ? null : (markers?.find((marker) => marker.propertyId === previewId) ?? null)),
    [markers, previewId],
  );

  /** 묶음은 마커와 표시 영역에만 달려 있다. 미리보기 상태가 바뀔 때마다 다시 묶지 않는다 */
  const grouped = useMemo<GroupedMarkers>(() => {
    if (isSeoul || !stageBbox) return { clusters: [], singles: markers ?? [] };
    return groupMarkers(markers ?? [], stageBbox);
  }, [isSeoul, markers, stageBbox]);

  const overlayItems = useMemo<OverlayItem[]>(() => {
    if (isSeoul) {
      return (districtCountsQuery.data?.districts ?? [])
        .map((district): OverlayItem | null => {
          const point = districtPoints[district.name];
          if (!point) return null;
          return {
            key: `district:${district.name}`,
            lat: point.lat,
            lng: point.lng,
            // 가리킨 말풍선만 앞으로. 나머지는 건수가 많은 쪽이 위에 온다
            zIndex: district.name === frontDistrict ? OVERLAY_Z_FRONT : (districtRank.get(district.name) ?? 0),
            node: (
              <DistrictOverlayContent
                district={district}
                onSelect={onSelectDistrict}
                onHover={handleHoverDistrict}
              />
            ),
          };
        })
        .filter((item): item is OverlayItem => item !== null);
    }

    const items: OverlayItem[] = grouped.clusters.map((cluster) => ({
      key: cluster.key,
      lat: cluster.lat,
      lng: cluster.lng,
      node: <MarkerClusterContent cluster={cluster} onSelect={handleSelectCluster} />,
    }));

    for (const marker of grouped.singles) {
      items.push({
        key: `marker:${marker.propertyId}`,
        lat: marker.latitude,
        lng: marker.longitude,
        node: (
          <PropertyMarkerContent
            marker={marker}
            isSelected={marker.propertyId === previewId}
            onSelect={handleSelectMarker}
            onHover={handleHoverMarker}
          />
        ),
      });
    }

    if (previewMarker) {
      items.push({
        key: `preview:${previewMarker.propertyId}`,
        lat: previewMarker.latitude,
        lng: previewMarker.longitude,
        node: (
          <div className={styles.preview}>
            <MarkerPreviewCard marker={previewMarker} onClose={handleClosePreview} onOpenDetail={onOpenDetail} />
          </div>
        ),
      });
    }

    return items;
  }, [
    districtCountsQuery.data,
    districtPoints,
    districtRank,
    grouped,
    frontDistrict,
    handleClosePreview,
    handleHoverDistrict,
    handleHoverMarker,
    handleSelectCluster,
    handleSelectMarker,
    isSeoul,
    onOpenDetail,
    onSelectDistrict,
    previewId,
    previewMarker,
  ]);

  const elementFor = useCallback(
    (key: string): HTMLDivElement => {
      const existing = containers.get(key);
      if (existing) return existing;
      const created = document.createElement('div');
      containers.set(key, created);
      return created;
    },
    [containers],
  );

  useEffect(() => {
    const layer = layerRef.current;
    if (!layer) return;

    layer.sync(
      overlayItems.map((item) => ({
        key: item.key,
        lat: item.lat,
        lng: item.lng,
        zIndex: item.zIndex,
        element: elementFor(item.key),
      })),
    );

    const alive = new Set(overlayItems.map((item) => item.key));
    for (const key of [...containers.keys()]) {
      if (!alive.has(key)) containers.delete(key);
    }
  }, [containers, elementFor, overlayItems]);

  const queryError = districtCountsQuery.error ?? markersQuery.error;

  return (
    <div className={styles.wrapper}>
      <div ref={containerRef} className={styles.map} role="application" aria-label="매물 지도" />

      {overlayItems.map((item) => createPortal(item.node, elementFor(item.key), item.key))}

      {(isSdkMissing || districtError || queryError) && (
        <div className={styles.notice}>
          {isSdkMissing && <Alert variant="error">지도를 불러오지 못했습니다. 새로고침해 주세요.</Alert>}
          {districtError && <Alert variant="error">{districtError}</Alert>}
          {queryError && <Alert variant="error">{queryError.message}</Alert>}
        </div>
      )}
    </div>
  );
}
