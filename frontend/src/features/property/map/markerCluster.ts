import type { BoundingBox, PropertyMapCluster } from '../../../api/property';
import type { RiskGrade } from '../../../domain/risk';

/**
 * 묶음 오버레이(MarkerClusterContent)가 그리는 모양. 묶는 규칙은 서버가 갖고(명세 1.12),
 * 화면은 응답의 칸을 이 모양으로 옮겨 그대로 그린다 — 다시 묶거나 세지 않는다.
 */
export interface MarkerCluster {
  /** 오버레이 키. 개별 마커(`marker:`) · 자치구(`district:`)와 겹치지 않게 접두를 붙인다 */
  key: string;
  /** 칸 안 매물의 평균 좌표 */
  lat: number;
  lng: number;
  count: number;
  /** 등급별 건수. 미분석은 unanalyzed — 열거값이 아니므로 domain/risk.ts의 미분석 처리와 같은 자리다 */
  gradeCounts: Record<RiskGrade, number> & { unanalyzed: number };
  /** 누르면 확대해 들어갈 칸 경계 */
  bbox: BoundingBox;
}

/** 지도 묶음 응답의 칸 하나 → 묶음 오버레이의 모양. 필드를 옮기기만 한다 */
export function toMarkerCluster(cluster: PropertyMapCluster): MarkerCluster {
  const { SAFE, CAUTION, DANGER, UNANALYZED } = cluster.gradeCounts;
  return {
    key: `cluster:${cluster.key}`,
    lat: cluster.latitude,
    lng: cluster.longitude,
    count: cluster.count,
    gradeCounts: { SAFE, CAUTION, DANGER, unanalyzed: UNANALYZED },
    bbox: {
      minLat: cluster.minLat,
      maxLat: cluster.maxLat,
      minLng: cluster.minLng,
      maxLng: cluster.maxLng,
    },
  };
}
