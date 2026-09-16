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
}

interface MapExplorerProps {
  filter: PropertyFilter;
  stage: MapStage;
  onSelectDistrict: (district: string) => void;
}

/**
 * 지도 드릴다운 1 · 2단계. 오버레이 내용은 React 컴포넌트를 포털로 그리고,
 * CustomOverlay의 생성 · 제거는 map 폴더가 맡는다 (kakao-map 5장).
 */
export function MapExplorer({ filter, stage, onSelectDistrict }: MapExplorerProps) {
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

    // 컨테이너 크기가 바뀌면 타일이 어긋난다 (kakao-map 5장)
    const handleResize = () => relayoutMap(map);
    window.addEventListener('resize', handleResize);

    // 컨테이너 크기가 잡히기 전에 만들어졌으면 타일이 안 그려지고 확대 수준도 엉뚱하다.
    // 크기가 확정된 다음 프레임에 서울 전체로 다시 맞춘다
    const relayoutFrame = requestAnimationFrame(() => {
      lockSeoulView(map);
      setView({ stageKey: stageKeyRef.current, bbox: readBoundingBox(map) });
    });

    return () => {
      cancelAnimationFrame(relayoutFrame);
      window.removeEventListener('resize', handleResize);
      removeIdle();
      removeClick();
      layerRef.current?.clear();
      layerRef.current = null;
      mapRef.current = null;
      containers.clear();
    };
  }, [containers, isSdkMissing]);

  // 단계 이동. 자치구는 Geocoder가 돌려준 중심으로 옮기고, 그 뒤 첫 idle이 표시 영역을 읽는다.
  // idle이 오지 않는 경우를 대비해 이동 직후에도 한 번 읽는다 (kakao-map 6장)
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    setPreviewId(null);
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

  const districtNames = useMemo(
    () => (districtCountsQuery.data?.districts ?? []).map((district) => district.name),
    [districtCountsQuery.data],
  );
  const districtPoints = useDistrictPoints(isSeoul ? districtNames : []);

  const handleHoverMarker = useCallback((propertyId: number) => setPreviewId(propertyId), []);
  const handleSelectMarker = useCallback((propertyId: number) => setPreviewId(propertyId), []);
  const handleClosePreview = useCallback(() => setPreviewId(null), []);
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
            node: <DistrictOverlayContent district={district} onSelect={onSelectDistrict} />,
          };
        })
        .filter((item): item is OverlayItem => item !== null);
    }

    const grouped: GroupedMarkers = stageBbox
      ? groupMarkers(markers ?? [], stageBbox)
      : { clusters: [], singles: markers ?? [] };

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
            <MarkerPreviewCard marker={previewMarker} onClose={handleClosePreview} />
          </div>
        ),
      });
    }

    return items;
  }, [
    districtCountsQuery.data,
    districtPoints,
    handleClosePreview,
    handleHoverMarker,
    handleSelectCluster,
    handleSelectMarker,
    isSeoul,
    markers,
    onSelectDistrict,
    previewId,
    previewMarker,
    stageBbox,
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
      overlayItems.map((item) => ({ key: item.key, lat: item.lat, lng: item.lng, element: elementFor(item.key) })),
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
