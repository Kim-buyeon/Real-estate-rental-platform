// 매물 API 명세 — 명세 표의 행 하나 = 함수 하나. 지금은 자치구 집계(PROP-08) · 매물 목록(PROP-01) ·
// 지도 묶음(PROP-02) · 상세(PROP-03) · 건축물대장(PROP-04) · 관심 매물 3행(PROP-05)이다.
// 매물 조회(GET /api/properties)의 좌표 조건 형태(명세 1.3 마커 응답)는 화면이 쓰지 않는다 — 지도 자치구 단계는
// 지도 묶음 조회(명세 1.12)로 옮겨 갔다. 쓰는 화면이 없는 형태에 함수를 두지 않는다.
import type { ContractType, PropertySort, PropertyType } from '../domain/property';
import type { PriceType, RiskGrade } from '../domain/risk';
import { request } from './client';
import type { CursorPage } from './types';

/**
 * 공통 검색 필터 — 명세 1.1. 자치구 집계와 매물 조회가 같은 파라미터를 쓰므로 한 번만 정의한다.
 * undefined와 빈 배열은 axios가 쿼리 문자열에서 빼고, 배열은 같은 키 반복으로 나간다 — client.ts의 paramsSerializer.
 */
export interface PropertyFilter {
  district?: string;
  contractType?: ContractType;
  /** 보증금 범위 (원) */
  depositMin?: number;
  depositMax?: number;
  /** 월세 상한 (원) */
  monthlyRentMax?: number;
  propertyType?: PropertyType;
  /** SAFE · CAUTION · DANGER 다중 선택 */
  riskGrade?: RiskGrade[];
  /** 전용면적 범위 (㎡) */
  areaMin?: number;
  areaMax?: number;
}

/** 지도 표시 영역 좌표 — 명세 1.12. 지도 묶음 조회의 필수 파라미터다 */
export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

/**
 * 지도 마커 응답 항목 — 명세 1.4. 지도 묶음 응답(1.12)의 markers가 이 필드 그대로다.
 * 분석 이력이 없는 매물은 riskGrade · debtRatio가 null이다
 */
export interface PropertyMarker {
  propertyId: number;
  latitude: number;
  longitude: number;
  /** 보증금 (원) */
  deposit: number;
  riskGrade: RiskGrade | null;
  contractType: ContractType;
  /** 월세 (원). 전세는 0 */
  monthlyRent: number;
  district: string;
  /** 전세가율 (%) */
  debtRatio: number | null;
  hasSeniorDebt: boolean;
}

/**
 * 격자 한 칸의 묶음 — 명세 1.12 clusters[]. 칸에 매물이 두 건 이상일 때만 온다.
 * gradeCounts의 UNANALYZED는 분석 이력이 없는 매물 수다 — 열거값이 아니라 이 응답의 키다.
 */
export interface PropertyMapCluster {
  /** 칸 식별자 `행:열`. 같은 표시 영역 안에서만 유일하다 */
  key: string;
  /** 칸 안 매물의 평균 좌표 — 묶음 표시 위치 */
  latitude: number;
  longitude: number;
  count: number;
  gradeCounts: Record<RiskGrade | 'UNANALYZED', number>;
  /** 칸 경계 — 선택 시 확대할 영역 */
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

/**
 * 지도 묶음 응답 — 명세 1.12. 표시 영역의 매물이 40건 이하면 clustered가 false이고 전부 markers로 온다.
 * 넘으면 두 건 이상인 칸은 clusters, 한 건뿐인 칸은 markers다. 묶는 규칙은 서버가 갖는다.
 */
export interface PropertyMapClusters {
  /** 표시 영역 안 매물 수(필터 적용) */
  total: number;
  clustered: boolean;
  clusters: PropertyMapCluster[];
  markers: PropertyMarker[];
}

/** 자치구 하나의 집계 — 명세 1.5. gradeCounts는 건수가 0인 등급이 빠질 수 있다 */
export interface DistrictCount {
  name: string;
  count: number;
  gradeCounts: Partial<Record<RiskGrade, number>>;
}

export interface DistrictCountList {
  districts: DistrictCount[];
  totalCount: number;
  /** 집계 기준 시각 (ISO 8601) */
  aggregatedAt: string;
}

/** PROP-08 · GET /api/properties/district-counts — 서울 전체 단계 */
export const fetchDistrictCounts = (filter: PropertyFilter) =>
  request<DistrictCountList>({ url: '/properties/district-counts', params: { ...filter } });

/** PROP-02 · GET /api/properties/map-clusters — 지도 자치구 단계. 표시 영역 넷 모두 필수 (명세 1.12) */
export const fetchPropertyMapClusters = (filter: PropertyFilter, bbox: BoundingBox) =>
  request<PropertyMapClusters>({ url: '/properties/map-clusters', params: { ...filter, ...bbox } });

/**
 * 목록 조회 응답 항목 — 명세 1.6. 좌표 조건 없이 조회했을 때의 형태이며 마커 응답(1.4)과 필드가 다르다.
 * riskGrade · debtRatio는 분석 이력이 없으면 null이다 — 두 응답이 같은 최신 분석 결과를 쓴다 (명세 1.4 마지막 줄).
 */
export interface PropertyListItem {
  propertyId: number;
  district: string;
  address: string;
  propertyType: PropertyType;
  contractType: ContractType;
  /** 보증금 (원) */
  deposit: number;
  /** 월세 (원). 전세는 0 */
  monthlyRent: number;
  /** 전용면적 (㎡) */
  areaSqm: number;
  floor: number;
  riskGrade: RiskGrade | null;
  /** 전세가율 (%) */
  debtRatio: number | null;
  /** 등록 일시 (ISO 8601) */
  registeredAt: string;
}

/**
 * PROP-01 · GET /api/properties (좌표 조건 없음 → 목록 형태) — 지도 옆 패널의 목록 탭.
 *
 * 좌표를 보내면 마커 형태로 응답하므로(명세 1.3) 여기서는 보내지 않는다 —
 * PropertyFilter에 좌표가 없는 것이 그 보장이고, BoundingBox는 지도 묶음 함수만 받는다.
 * 정렬을 지정하지 않으면 서버가 등록일 내림차순을 적용한다(명세 1.3) — 화면이 기본값을 박지 않는다.
 * size도 보내지 않는다 — 공통 규약 1.4의 기본값 20을 쓴다 (fetchWishlist와 같은 판단).
 */
export const fetchPropertyList = (filter: PropertyFilter, sort?: PropertySort, cursor?: string) =>
  request<CursorPage<PropertyListItem>>({ url: '/properties', params: { ...filter, sort, cursor } });

/**
 * 매물 상세의 최신 위험도 요약 — 명세 1.7 riskSummary.
 * 판정 근거 전체는 위험도 조회(위험도 명세 1.1)가 주며, 여기는 등급 · 전세가율 · 가입 가능 여부만이다.
 */
export interface PropertyRiskSummary {
  riskGrade: RiskGrade;
  /** 전세가율 (%) */
  debtRatio: number;
  insuranceEligible: boolean;
}

/** 매물 상세 응답 — 명세 1.7 */
export interface PropertyDetail {
  propertyId: number;
  district: string;
  address: string;
  latitude: number;
  longitude: number;
  propertyType: PropertyType;
  contractType: ContractType;
  /** 보증금 (원) */
  deposit: number;
  /** 월세 (원). 전세는 0 */
  monthlyRent: number;
  /** 전용면적 (㎡) */
  areaSqm: number;
  floor: number;
  landlordName: string;
  /** 적용 시세 (원)와 산출 근거 · 기준일 (PROP-03) */
  marketPrice: number;
  priceType: PriceType;
  /** 시세 기준일 (YYYY-MM-DD) */
  priceDate: string;
  /** 분석 이력이 없는 매물은 null — 미분석 (명세 1.4 마지막 줄, 백엔드 PropertyDetailResponse) */
  riskSummary: PropertyRiskSummary | null;
  /** 관심 매물 등록 여부. 인증 「선택」이므로 비로그인은 항상 false */
  wishlisted: boolean;
  /** 등록 일시 (ISO 8601) */
  registeredAt: string;
}

/** PROP-03 · GET /api/properties/{propertyId} — 상세 패널 */
export const fetchPropertyDetail = (propertyId: number) =>
  request<PropertyDetail>({ url: `/properties/${propertyId}` });

/**
 * 건축물대장 — 명세 1.8. 국토부 수집 데이터이며 명의 · 문서 정합 확인(RISK-04)의 입력이기도 하다.
 * 위험도 응답의 consistency가 그 대조 「결과」라면 이쪽은 대조에 쓰인 원본이다.
 */
export interface BuildingLedger {
  propertyId: number;
  /** 건축물대장의 주용도. 공통 코드가 아니라 대장이 준 문자열 그대로다 */
  mainPurpose: string;
  isResidential: boolean;
  /** 참일 때가 문제다 — 보증보험 집 단위 조건에 걸린다 (RISK-05) */
  violationBuilding: boolean;
  /** 연면적 (㎡) */
  totalFloorArea: number;
  /** 전용면적 (㎡) */
  exclusiveArea: number;
  /** 사용승인일 (YYYY-MM-DD) */
  approvalDate: string;
  /** 대장 수집 시각 (ISO 8601) */
  collectedAt: string;
}

/**
 * PROP-04 · GET /api/properties/{propertyId}/ledger — 인증 선택.
 * 상세 진입 시 호출하지 않는다 — 명세 1.4 「탐색 동작과 호출 시점」 표가 그때의 호출을
 * 매물 상세와 위험도 둘로 정한다. 패널에서 펼칠 때 부른다.
 */
export const fetchBuildingLedger = (propertyId: number) =>
  request<BuildingLedger>({ url: `/properties/${propertyId}/ledger` });

/**
 * 관심 매물 목록 한 건 — 명세 1.9. 정렬은 등록 역순이며 서버가 그렇게 준다 (화면에서 다시 정렬하지 않는다).
 * riskGrade는 최신 분석의 등급, previousGrade는 그 직전 등급이다. 아직 분석되지 않았으면 riskGrade가,
 * 분석 전이거나 첫 분석이면 previousGrade가 null이다 — 미분석 문구는 domain/risk.ts가 갖는다.
 */
export interface WishlistItem {
  propertyId: number;
  district: string;
  /** 보증금 (원) */
  deposit: number;
  riskGrade: RiskGrade | null;
  previousGrade: RiskGrade | null;
  /** 관심 매물 등록 일시 (ISO 8601) */
  addedAt: string;
}

/**
 * PROP-05 · GET /api/me/wishlist — 인증 필수. 커서 목록이다 (공통 규약 1.4).
 * size는 보내지 않는다 — 공통 규약 1.4의 기본값 20을 서버가 쓴다. 화면이 쪽 크기를 정할 이유가 없다.
 */
export const fetchWishlist = (cursor?: string) =>
  request<CursorPage<WishlistItem>>({ url: '/me/wishlist', params: { cursor } });

/** PROP-05 · POST /api/me/wishlist — 성공은 201, 이미 등록한 매물은 409 WISHLIST_DUPLICATED (명세 1.9) */
export const addWishlist = (propertyId: number) =>
  request<null>({ method: 'POST', url: '/me/wishlist', data: { propertyId } });

/** PROP-05 · DELETE /api/me/wishlist/{propertyId} — 멱등이다. 등록되지 않은 매물이어도 204 (명세 1.9) */
export const removeWishlist = (propertyId: number) =>
  request<null>({ method: 'DELETE', url: `/me/wishlist/${propertyId}` });
