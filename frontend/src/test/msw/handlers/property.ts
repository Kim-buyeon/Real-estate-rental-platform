// 매물 도메인 MSW 핸들러. 응답은 매물 API 명세 1.5 · 1.7 · 1.8의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { BuildingLedger, DistrictCountList, PropertyDetail } from '../../../api/property';

const DISTRICT_COUNTS: DistrictCountList = {
  districts: [
    { name: '강서구', count: 290, gradeCounts: { SAFE: 180, CAUTION: 82, DANGER: 28 } },
    { name: '구로구', count: 250, gradeCounts: { SAFE: 150, CAUTION: 70, DANGER: 30 } },
  ],
  totalCount: 540,
  aggregatedAt: '2026-07-29T10:05:00+09:00',
};

/** 매물 API 명세 1.7 응답 예시 그대로 — PropertyDetailPanel 테스트가 쓴다 */
export const PROPERTY_DETAIL: PropertyDetail = {
  propertyId: 1024,
  district: '강서구',
  address: '서울특별시 강서구 화곡로 123',
  latitude: 37.5501234,
  longitude: 126.8497561,
  // 명세 1.7 예시는 MULTIPLEX(연립다세대)이나 프론트 PropertyType은 지금 APARTMENT · OFFICETEL만 지원한다
  // (domain/property.ts) — 타입에 맞춰 APARTMENT로 둔다
  propertyType: 'APARTMENT',
  contractType: 'DEPOSIT_ONLY',
  deposit: 230000000,
  monthlyRent: 0,
  areaSqm: 42.5,
  floor: 3,
  landlordName: '김임대',
  marketPrice: 340000000,
  priceType: 'ACTUAL_TRANSACTION',
  priceDate: '2026-06-30',
  riskSummary: { riskGrade: 'SAFE', debtRatio: 68.0, insuranceEligible: true },
  wishlisted: false,
  registeredAt: '2026-07-20T14:03:00+09:00',
};

/** 매물 API 명세 1.8 응답 예시 그대로 — BuildingLedgerSection 테스트가 쓴다 */
export const BUILDING_LEDGER: BuildingLedger = {
  propertyId: 1024,
  mainPurpose: '공동주택',
  isResidential: true,
  violationBuilding: false,
  totalFloorArea: 480.2,
  exclusiveArea: 42.5,
  approvalDate: '2015-04-18',
  collectedAt: '2026-07-28T02:10:00+09:00',
};

export const propertyHandlers = [
  http.get('/api/properties/district-counts', () => HttpResponse.json({ success: true, data: DISTRICT_COUNTS })),
  http.get('/api/properties/:propertyId/ledger', () => HttpResponse.json({ success: true, data: BUILDING_LEDGER })),
  http.get('/api/properties/:propertyId', () => HttpResponse.json({ success: true, data: PROPERTY_DETAIL })),
];
