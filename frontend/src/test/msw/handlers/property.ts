// 매물 도메인 MSW 핸들러. 응답은 매물 API 명세 1.5 · 1.6 · 1.7 · 1.8 · 1.9 · 1.12의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type {
  BuildingLedger,
  DistrictCountList,
  PropertyDetail,
  PropertyListItem,
  PropertyMapClusters,
  WishlistItem,
} from '../../../api/property';
import type { CursorPage } from '../../../api/types';

const DISTRICT_COUNTS: DistrictCountList = {
  districts: [
    { name: '강서구', count: 290, gradeCounts: { SAFE: 180, CAUTION: 82, DANGER: 28 } },
    { name: '구로구', count: 250, gradeCounts: { SAFE: 150, CAUTION: 70, DANGER: 30 } },
  ],
  totalCount: 540,
  aggregatedAt: '2026-07-29T10:05:00+09:00',
};

/** 매물 API 명세 1.12 응답 예시 그대로 — 묶음 하나와 개별 마커 하나. MapExplorer 테스트가 쓴다 */
export const MAP_CLUSTERS: PropertyMapClusters = {
  total: 6003,
  clustered: true,
  clusters: [
    {
      key: '5:7',
      latitude: 37.5534,
      longitude: 126.8561,
      count: 214,
      gradeCounts: { SAFE: 80, CAUTION: 71, DANGER: 58, UNANALYZED: 5 },
      minLat: 37.545,
      maxLat: 37.55,
      minLng: 126.8567,
      maxLng: 126.8633,
    },
  ],
  markers: [
    {
      propertyId: 41408,
      latitude: 37.5791,
      longitude: 126.8104,
      deposit: 150000000,
      riskGrade: 'CAUTION',
      contractType: 'DEPOSIT_ONLY',
      monthlyRent: 0,
      district: '강서구',
      debtRatio: 74.5,
      hasSeniorDebt: false,
    },
  ],
};

/** 매물 API 명세 1.7 응답 예시 그대로 — PropertyDetailPanel 테스트가 쓴다 */
export const PROPERTY_DETAIL: PropertyDetail = {
  propertyId: 1024,
  district: '강서구',
  address: '서울특별시 강서구 화곡로 123',
  latitude: 37.5501234,
  longitude: 126.8497561,
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

/**
 * 목록 조회 응답 첫 쪽 — 명세 1.6 예시 그대로다. 항목 하나에 propertyId 1024로, PROPERTY_DETAIL과
 * 같은 매물이다(명세 1.7 예시도 같은 propertyId를 쓴다).
 */
export const PROPERTY_LIST_PAGE_1: CursorPage<PropertyListItem> = {
  items: [
    {
      propertyId: 1024,
      district: '강서구',
      address: '서울특별시 강서구 화곡로 123',
      propertyType: 'APARTMENT',
      contractType: 'DEPOSIT_ONLY',
      deposit: 230000000,
      monthlyRent: 0,
      areaSqm: 42.5,
      floor: 3,
      riskGrade: 'SAFE',
      debtRatio: 68.0,
      registeredAt: '2026-07-20T14:03:00+09:00',
    },
  ],
  nextCursor: 'eyJpZCI6MTAyNH0',
  hasNext: true,
};

/**
 * 목록 조회 응답 둘째(마지막) 쪽 — PROPERTY_LIST_PAGE_1과 이어 「더 보기」를 검증할 수 있게 나눈 쪽이다.
 * 항목은 riskGrade · debtRatio가 null인 미분석 매물이다 — 분석 이력이 없으면 등급 · 전세가율이 없다
 * (명세 1.4 마지막 줄과 같은 규칙, 1.6 응답도 같은 값을 쓴다).
 */
export const PROPERTY_LIST_PAGE_2: CursorPage<PropertyListItem> = {
  items: [
    {
      propertyId: 2048,
      district: '구로구',
      address: '서울특별시 구로구 경인로 45',
      propertyType: 'OFFICETEL',
      contractType: 'MONTHLY_RENT',
      deposit: 50000000,
      monthlyRent: 600000,
      areaSqm: 33.1,
      floor: 7,
      riskGrade: null,
      debtRatio: null,
      registeredAt: '2026-07-18T09:30:00+09:00',
    },
  ],
  nextCursor: null,
  hasNext: false,
};

/**
 * 매물 조회는 단일 엔드포인트로 좌표 유무가 응답 형태를 가른다(명세 1.3) — PROP-01은 목록 형태만
 * 쓰므로 여기서는 좌표가 없을 때의 분기만 다룬다. 좌표가 있으면(minLat 존재) 마커 형태가 와야
 * 목록 컴포넌트가 좌표를 잘못 보냈을 때 렌더가 깨져 테스트가 실패로 드러난다 — 빈 items로 충분하다.
 * cursor 파라미터로 쪽을 가른다: 없으면 첫 쪽, PROPERTY_LIST_PAGE_1.nextCursor 그대로 오면 둘째 쪽.
 */
export const propertyHandlers = [
  http.get('/api/properties/district-counts', () => HttpResponse.json({ success: true, data: DISTRICT_COUNTS })),
  // :propertyId보다 앞에 둔다 — 뒤에 두면 map-clusters가 매물 id로 잡힌다
  http.get('/api/properties/map-clusters', () => HttpResponse.json({ success: true, data: MAP_CLUSTERS })),
  http.get('/api/properties/:propertyId/ledger', () => HttpResponse.json({ success: true, data: BUILDING_LEDGER })),
  http.get('/api/properties/:propertyId', () => HttpResponse.json({ success: true, data: PROPERTY_DETAIL })),
  http.get('/api/properties', ({ request }) => {
    const url = new URL(request.url);
    if (url.searchParams.has('minLat')) {
      return HttpResponse.json({ success: true, data: { items: [], count: 0 } });
    }
    const cursor = url.searchParams.get('cursor');
    const page = cursor === PROPERTY_LIST_PAGE_1.nextCursor ? PROPERTY_LIST_PAGE_2 : PROPERTY_LIST_PAGE_1;
    return HttpResponse.json({ success: true, data: page });
  }),
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
