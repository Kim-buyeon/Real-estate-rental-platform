import { describe, expect, it } from 'vitest';
import type { BoundingBox, PropertyMarker } from '../../../api/property';
import { CLUSTER_THRESHOLD, GRID_DIVISIONS, groupMarkers } from './cluster';

const BBOX: BoundingBox = { minLat: 37.5, maxLat: 37.6, minLng: 126.8, maxLng: 126.9 };

function marker(propertyId: number, latitude: number, longitude: number, riskGrade: PropertyMarker['riskGrade'] = null): PropertyMarker {
  return {
    propertyId,
    latitude,
    longitude,
    deposit: 200_000_000,
    riskGrade,
    contractType: 'DEPOSIT_ONLY',
    monthlyRent: 0,
    district: '강서구',
    debtRatio: null,
    hasSeniorDebt: false,
  };
}

/** 같은 좌표에 n개 — 한 건물의 매물이 이렇게 온다 */
function stacked(count: number, latitude: number, longitude: number): PropertyMarker[] {
  return Array.from({ length: count }, (_, index) => marker(index + 1, latitude, longitude));
}

describe('groupMarkers', () => {
  it('임계 이하면 묶지 않고 전부 개별로 돌려준다', () => {
    const markers = stacked(CLUSTER_THRESHOLD, 37.55, 126.85);

    const { clusters, singles } = groupMarkers(markers, BBOX);

    expect(clusters).toHaveLength(0);
    expect(singles).toHaveLength(CLUSTER_THRESHOLD);
  });

  it('같은 좌표에 포개진 매물은 건수 하나로 묶는다', () => {
    const markers = stacked(CLUSTER_THRESHOLD + 1, 37.55, 126.85);

    const { clusters, singles } = groupMarkers(markers, BBOX);

    expect(singles).toHaveLength(0);
    expect(clusters).toHaveLength(1);
    expect(clusters[0]?.count).toBe(CLUSTER_THRESHOLD + 1);
    expect(clusters[0]?.lat).toBeCloseTo(37.55, 6);
    expect(clusters[0]?.lng).toBeCloseTo(126.85, 6);
  });

  it('셀에 한 건만 있으면 개별 마커로 남긴다', () => {
    const markers = [...stacked(CLUSTER_THRESHOLD, 37.505, 126.805), marker(999, 37.595, 126.895)];

    const { clusters, singles } = groupMarkers(markers, BBOX);

    expect(clusters).toHaveLength(1);
    expect(singles.map((item) => item.propertyId)).toEqual([999]);
  });

  it('묶음의 경계는 그 셀이며 확대해 들어갈 수 있다', () => {
    const markers = stacked(CLUSTER_THRESHOLD + 1, 37.505, 126.805);
    const cellLat = (BBOX.maxLat - BBOX.minLat) / GRID_DIVISIONS;

    const cluster = groupMarkers(markers, BBOX).clusters[0];

    expect(cluster?.bbox.minLat).toBeCloseTo(BBOX.minLat, 6);
    expect(cluster?.bbox.maxLat).toBeCloseTo(BBOX.minLat + cellLat, 6);
    expect(cluster?.bbox.maxLat).toBeLessThan(BBOX.maxLat);
  });

  it('표시 영역 밖 좌표도 가장자리 셀에 담는다 — 좌표를 밖으로 반올림해 조회한다', () => {
    const markers = [...stacked(CLUSTER_THRESHOLD, 37.4, 126.7), ...stacked(2, 37.7, 127.0)];

    const { clusters, singles } = groupMarkers(markers, BBOX);

    expect(singles).toHaveLength(0);
    expect(clusters).toHaveLength(2);
    expect(clusters.reduce((sum, cluster) => sum + cluster.count, 0)).toBe(CLUSTER_THRESHOLD + 2);
  });

  it('등급별 건수를 세고 분석 전 매물은 따로 센다', () => {
    const markers = [
      ...Array.from({ length: CLUSTER_THRESHOLD }, (_, index) => marker(index + 1, 37.55, 126.85, 'SAFE')),
      marker(101, 37.55, 126.85, 'DANGER'),
      marker(102, 37.55, 126.85),
    ];

    const cluster = groupMarkers(markers, BBOX).clusters[0];

    expect(cluster?.gradeCounts).toEqual({
      SAFE: CLUSTER_THRESHOLD,
      CAUTION: 0,
      DANGER: 1,
      unanalyzed: 1,
    });
  });

  it('표시 영역이 비어 있으면 묶지 않는다', () => {
    const markers = stacked(CLUSTER_THRESHOLD + 1, 37.5, 126.8);

    const { clusters, singles } = groupMarkers(markers, { minLat: 37.5, maxLat: 37.5, minLng: 126.8, maxLng: 126.8 });

    expect(clusters).toHaveLength(0);
    expect(singles).toHaveLength(CLUSTER_THRESHOLD + 1);
  });
});
