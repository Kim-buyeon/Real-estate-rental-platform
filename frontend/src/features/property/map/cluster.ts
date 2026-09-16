import type { BoundingBox, PropertyMarker } from '../../../api/property';
import type { RiskGrade } from '../../../domain/risk';
import { CLUSTER_THRESHOLD, GRID_DIVISIONS } from './constants';

export interface MarkerCluster {
  key: string;
  /** 묶인 매물의 평균 좌표 */
  lat: number;
  lng: number;
  count: number;
  /** 등급별 건수. 미분석은 null 키 대신 unanalyzed 로 센다 */
  gradeCounts: Record<RiskGrade, number> & { unanalyzed: number };
  /** 누르면 확대해 들어갈 셀 경계 */
  bbox: BoundingBox;
}

export interface GroupedMarkers {
  clusters: MarkerCluster[];
  singles: PropertyMarker[];
}

interface Cell {
  row: number;
  column: number;
  markers: PropertyMarker[];
}

/**
 * 표시 영역 안의 마커를 격자 셀로 묶는다. 한 건뿐인 셀은 개별 마커로 돌려준다.
 *
 * 같은 건물의 매물은 좌표가 같아 완전히 포개지므로, 묶지 않으면 위에 있는 하나만 고를 수 있다.
 */
export function groupMarkers(markers: PropertyMarker[], bbox: BoundingBox): GroupedMarkers {
  if (markers.length <= CLUSTER_THRESHOLD) {
    return { clusters: [], singles: [...markers] };
  }

  const latSpan = bbox.maxLat - bbox.minLat;
  const lngSpan = bbox.maxLng - bbox.minLng;
  if (latSpan <= 0 || lngSpan <= 0) {
    return { clusters: [], singles: [...markers] };
  }

  const cellLat = latSpan / GRID_DIVISIONS;
  const cellLng = lngSpan / GRID_DIVISIONS;
  const cells = new Map<string, Cell>();

  for (const marker of markers) {
    // 표시 영역 밖의 값이 와도 가장자리 셀에 담는다 — 좌표를 밖으로 반올림해 조회하므로 경계를 넘을 수 있다
    const row = clampIndex(Math.floor((marker.latitude - bbox.minLat) / cellLat));
    const column = clampIndex(Math.floor((marker.longitude - bbox.minLng) / cellLng));
    const key = `${row}:${column}`;
    const cell = cells.get(key);
    if (cell) {
      cell.markers.push(marker);
    } else {
      cells.set(key, { row, column, markers: [marker] });
    }
  }

  const clusters: MarkerCluster[] = [];
  const singles: PropertyMarker[] = [];

  for (const [key, cell] of cells) {
    const first = cell.markers[0];
    if (cell.markers.length === 1 && first) {
      singles.push(first);
      continue;
    }
    clusters.push(toCluster(key, cell, bbox, cellLat, cellLng));
  }

  return { clusters, singles };
}

function clampIndex(index: number): number {
  if (index < 0) return 0;
  if (index > GRID_DIVISIONS - 1) return GRID_DIVISIONS - 1;
  return index;
}

function toCluster(key: string, cell: Cell, bbox: BoundingBox, cellLat: number, cellLng: number): MarkerCluster {
  const gradeCounts = { SAFE: 0, CAUTION: 0, DANGER: 0, unanalyzed: 0 };
  let latSum = 0;
  let lngSum = 0;

  for (const marker of cell.markers) {
    latSum += marker.latitude;
    lngSum += marker.longitude;
    if (marker.riskGrade) {
      gradeCounts[marker.riskGrade] += 1;
    } else {
      gradeCounts.unanalyzed += 1;
    }
  }

  const minLat = bbox.minLat + cell.row * cellLat;
  const minLng = bbox.minLng + cell.column * cellLng;

  return {
    key: `cluster:${key}`,
    lat: latSum / cell.markers.length,
    lng: lngSum / cell.markers.length,
    count: cell.markers.length,
    gradeCounts,
    bbox: {
      minLat,
      maxLat: minLat + cellLat,
      minLng,
      maxLng: minLng + cellLng,
    },
  };
}
