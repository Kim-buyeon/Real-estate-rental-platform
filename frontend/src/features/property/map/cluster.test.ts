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

  it('셀 경계에 놓인 마커도 자기 셀 경계 안에 담긴다', () => {
    const cellLat = (BBOX.maxLat - BBOX.minLat) / GRID_DIVISIONS;
    const cellLng = (BBOX.maxLng - BBOX.minLng) / GRID_DIVISIONS;
    // 경계에 정확히 놓인 좌표가 위·아래 어느 셀로 가는지는 부동소수 오차로 정해지지 않는다.
    // 보장해야 하는 것은 「어느 쪽으로 가든 그 셀의 경계가 자기 마커를 포함한다」이다.
    const onBoundary = BBOX.minLat + cellLat;
    const markers = [
      ...stacked(CLUSTER_THRESHOLD, onBoundary, BBOX.minLng + cellLng),
      ...stacked(2, onBoundary + cellLat / 2, BBOX.minLng + cellLng / 2),
    ];

    const { clusters } = groupMarkers(markers, BBOX);

    expect(clusters.length).toBeGreaterThan(0);
    for (const cluster of clusters) {
      expect(cluster.lat).toBeGreaterThanOrEqual(cluster.bbox.minLat - 1e-9);
      expect(cluster.lat).toBeLessThanOrEqual(cluster.bbox.maxLat + 1e-9);
      expect(cluster.lng).toBeGreaterThanOrEqual(cluster.bbox.minLng - 1e-9);
      expect(cluster.lng).toBeLessThanOrEqual(cluster.bbox.maxLng + 1e-9);
    }
  });

  it('표시 영역의 최대 좌표는 마지막 셀에 담긴다', () => {
    const markers = stacked(CLUSTER_THRESHOLD + 1, BBOX.maxLat, BBOX.maxLng);
    const cellLat = (BBOX.maxLat - BBOX.minLat) / GRID_DIVISIONS;

    const cluster = groupMarkers(markers, BBOX).clusters[0];

    // 나누면 GRID_DIVISIONS 번째 셀이 되지만 마지막 셀로 clamp 한다
    expect(cluster?.bbox.maxLat).toBeCloseTo(BBOX.maxLat, 6);
    expect(cluster?.bbox.minLat).toBeCloseTo(BBOX.maxLat - cellLat, 6);
  });

  it('표시 영역이 비어 있으면 묶지 않는다', () => {
    const markers = stacked(CLUSTER_THRESHOLD + 1, 37.5, 126.8);

    const { clusters, singles } = groupMarkers(markers, { minLat: 37.5, maxLat: 37.5, minLng: 126.8, maxLng: 126.8 });

    expect(clusters).toHaveLength(0);
    expect(singles).toHaveLength(CLUSTER_THRESHOLD + 1);
  });
});
