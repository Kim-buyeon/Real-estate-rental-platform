import { describe, expect, it } from 'vitest';
import type { PropertyMapCluster } from '../../../api/property';
import { toMarkerCluster } from './markerCluster';

/** 매물 API 명세 1.12 응답 예시의 칸 그대로 */
const CLUSTER: PropertyMapCluster = {
  key: '5:7',
  latitude: 37.5534,
  longitude: 126.8561,
  count: 214,
  gradeCounts: { SAFE: 80, CAUTION: 71, DANGER: 58, UNANALYZED: 5 },
  minLat: 37.545,
  maxLat: 37.55,
  minLng: 126.8567,
  maxLng: 126.8633,
};

describe('toMarkerCluster', () => {
  it('서버 칸의 좌표 · 건수 · 경계를 그대로 옮긴다', () => {
    expect(toMarkerCluster(CLUSTER)).toEqual({
      key: 'cluster:5:7',
      lat: 37.5534,
      lng: 126.8561,
      count: 214,
      gradeCounts: { SAFE: 80, CAUTION: 71, DANGER: 58, unanalyzed: 5 },
      bbox: { minLat: 37.545, maxLat: 37.55, minLng: 126.8567, maxLng: 126.8633 },
    });
  });
});
