// 매물 도메인 MSW 핸들러. 응답은 매물 API 명세 1.5의 예시 모양을 따르되, districts는 비워
// 자치구 중심 좌표 조회(Geocoder)를 트리거하지 않는다 — SDK가 없는 테스트 환경에서는 그 경로가 던진다.
import { http, HttpResponse } from 'msw';
import type { DistrictCountList } from '../../../api/property';

const EMPTY_DISTRICT_COUNTS: DistrictCountList = {
  districts: [],
  totalCount: 0,
  aggregatedAt: '2026-07-29T10:05:00+09:00',
};

export const propertyHandlers = [
  http.get('/api/properties/district-counts', () =>
    HttpResponse.json({ success: true, data: EMPTY_DISTRICT_COUNTS }),
  ),
];
