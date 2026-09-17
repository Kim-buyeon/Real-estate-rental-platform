// 매물 도메인 MSW 핸들러. 응답은 매물 API 명세 1.5 · 1.7 · 1.8 · 1.9의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { BuildingLedger, DistrictCountList, PropertyDetail, WishlistItem } from '../../../api/property';
import type { CursorPage } from '../../../api/types';

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

/**
 * 관심 매물 목록 첫 쪽 — 명세 1.9. 첫 항목은 명세 예시 그대로(propertyId 1024)다.
 * 둘째 항목은 previousGrade가 null인 「첫 분석」 조합이다 — riskGrade는 있고 previousGrade만 없다.
 * hasNext: true라 WishlistList.test.tsx의 「더 보기」가 이 쪽에서 둘째 쪽으로 잇는다.
 */
export const WISHLIST_PAGE_1: CursorPage<WishlistItem> = {
  items: [
    {
      propertyId: 1024,
      district: '강서구',
      deposit: 230000000,
      riskGrade: 'SAFE',
      previousGrade: 'CAUTION',
      addedAt: '2026-07-25T11:20:00+09:00',
    },
    {
      propertyId: 2048,
      district: '구로구',
      deposit: 180000000,
      riskGrade: 'CAUTION',
      previousGrade: null,
      addedAt: '2026-07-24T09:00:00+09:00',
    },
  ],
  nextCursor: 'eyJpZCI6MjA0OH0',
  hasNext: true,
};

/**
 * 관심 매물 목록 둘째(마지막) 쪽. 항목은 riskGrade · previousGrade가 모두 null인 「미분석」 조합이다
 * — 분석 이력이 없으면 직전 등급도 있을 수 없다(명세 1.9). hasNext: false이므로 이 쪽을 받은 뒤
 * WishlistList의 「더 보기」가 사라진다.
 */
export const WISHLIST_PAGE_2: CursorPage<WishlistItem> = {
  items: [
    {
      propertyId: 3072,
      district: '송파구',
      deposit: 300000000,
      riskGrade: null,
      previousGrade: null,
      addedAt: '2026-07-20T08:00:00+09:00',
    },
  ],
  nextCursor: null,
  hasNext: false,
};

export const propertyHandlers = [
  http.get('/api/properties/district-counts', () => HttpResponse.json({ success: true, data: DISTRICT_COUNTS })),
  http.get('/api/properties/:propertyId/ledger', () => HttpResponse.json({ success: true, data: BUILDING_LEDGER })),
  http.get('/api/properties/:propertyId', () => HttpResponse.json({ success: true, data: PROPERTY_DETAIL })),
];

/**
 * 관심 매물(PROP-05) 핸들러 — 명세 1.9. GET은 cursor 파라미터로 쪽을 가른다: 없으면 첫 쪽,
 * WISHLIST_PAGE_1.nextCursor 그대로 오면 둘째 쪽 — 공통 규약 1.4(응답의 nextCursor를 다음 요청에
 * 그대로 넣는다)를 어긴 커서면 첫 쪽으로 되돌아간다.
 * POST는 성공 시 201 + data: null, DELETE는 멱등이라 항상 204(명세 1.9).
 */
export const wishlistHandlers = [
  http.get('/api/me/wishlist', ({ request }) => {
    const cursor = new URL(request.url).searchParams.get('cursor');
    const page = cursor === WISHLIST_PAGE_1.nextCursor ? WISHLIST_PAGE_2 : WISHLIST_PAGE_1;
    return HttpResponse.json({ success: true, data: page });
  }),
  http.post('/api/me/wishlist', () => HttpResponse.json({ success: true, data: null }, { status: 201 })),
  http.delete('/api/me/wishlist/:propertyId', () => new HttpResponse(null, { status: 204 })),
];
