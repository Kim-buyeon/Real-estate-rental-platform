// 매물 도메인 MSW 핸들러. 응답은 매물 API 명세 1.5의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { DistrictCountList } from '../../../api/property';

const DISTRICT_COUNTS: DistrictCountList = {
  districts: [
    { name: '강서구', count: 290, gradeCounts: { SAFE: 180, CAUTION: 82, DANGER: 28 } },
    { name: '구로구', count: 250, gradeCounts: { SAFE: 150, CAUTION: 70, DANGER: 30 } },
  ],
  totalCount: 540,
  aggregatedAt: '2026-07-29T10:05:00+09:00',
};

export const propertyHandlers = [
  http.get('/api/properties/district-counts', () => HttpResponse.json({ success: true, data: DISTRICT_COUNTS })),
];
