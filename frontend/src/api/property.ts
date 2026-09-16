// 매물 API 명세 — 명세 표의 행 하나 = 함수 하나. 이번 범위는 자치구 집계(PROP-08)와 지도 마커(PROP-02) 둘이다.
// 상세 · 건축물대장 · 관심 매물 함수는 그 슬라이스가 이 파일에 추가한다.
import type { ContractType, PropertyType } from '../domain/property';
import type { RiskGrade } from '../domain/risk';
import { request } from './client';

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

/** 지도 표시 영역 좌표 — 명세 1.3. 이 조건이 있으면 응답이 목록이 아니라 마커 형태다 */
export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

/** 지도 마커 응답 항목 — 명세 1.4. 분석 이력이 없는 매물은 riskGrade · debtRatio가 null이다 */
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

/** 표시 영역 전체를 그려야 하므로 커서 페이지네이션을 쓰지 않는다 — 명세 1.3 */
export interface PropertyMarkerList {
  items: PropertyMarker[];
  count: number;
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

/** PROP-02 · GET /api/properties (좌표 조건 있음 → 마커 형태) — 자치구 단계 */
export const fetchPropertyMarkers = (filter: PropertyFilter, bbox: BoundingBox) =>
  request<PropertyMarkerList>({ url: '/properties', params: { ...filter, ...bbox } });
