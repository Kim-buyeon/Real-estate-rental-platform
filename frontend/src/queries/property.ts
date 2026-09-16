// 매물 쿼리 정의. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
// 상세(detail) 정의는 상세 패널 슬라이스가 여기에 추가한다.
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import {
  fetchDistrictCounts,
  fetchPropertyMarkers,
  type BoundingBox,
  type PropertyFilter,
} from '../api/property';

export const propertyQueries = {
  /** 도메인 루트. 무효화 연쇄(재분석 · SSE 위험도 변경)가 이 키로 걸린다 */
  all: () => ['property'] as const,

  /**
   * PROP-08 자치구 집계. 필터가 전부 키에 들어간다 —
   * 키에 없는 값으로 요청하면 다른 조건의 결과가 캐시에서 나온다.
   */
  districtCounts: (filter: PropertyFilter) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'districtCounts', filter] as const,
      queryFn: () => fetchDistrictCounts(filter),
      // 필터를 바꾸는 동안 이전 집계를 유지한다 — 지도가 비지 않는다
      placeholderData: keepPreviousData,
    }),

  /** PROP-02 지도 마커. 표시 영역 좌표도 요청을 바꾸므로 키에 들어간다 (kakao-map 4장) */
  markers: (filter: PropertyFilter, bbox: BoundingBox) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'markers', filter, bbox] as const,
      queryFn: () => fetchPropertyMarkers(filter, bbox),
      // 지도를 움직이는 동안 이전 마커를 유지한다
      placeholderData: keepPreviousData,
    }),
};
