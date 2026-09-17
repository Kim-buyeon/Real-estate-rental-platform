// 매물 쿼리 정의. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
// 관심 매물 정의는 그 슬라이스가 여기에 추가한다.
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import {
  fetchBuildingLedger,
  fetchDistrictCounts,
  fetchPropertyDetail,
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

  /**
   * PROP-03 매물 상세 — 지도 옆 패널. 관심 매물 등록 · 해제와 재분석의 무효화가 이 키로 걸린다
   * (wishlisted · riskSummary가 바뀐다 — frontend/CLAUDE.md 무효화 연쇄).
   */
  detail: (propertyId: number) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'detail', propertyId] as const,
      queryFn: () => fetchPropertyDetail(propertyId),
    }),

  /**
   * PROP-04 건축물대장. 상세 진입 시 부르지 않고 패널에서 펼칠 때 부른다 — 화면이 닫혀 있는 동안
   * 이 컴포넌트를 마운트하지 않는 것으로 그 시점을 정하고, 여기서는 키와 함수만 둔다
   * (명세 1.4 「탐색 동작과 호출 시점」).
   * 수집 데이터라 재분석으로 바뀌지 않는다 — 개별 항목으로는 무효화 연쇄에 없고, 등급이 바뀐 재분석
   * (gradeChanged)의 property 전체 쓸기에만 이 키도 함께 들어간다 (queries/risk.ts useReanalyzeRisk).
   */
  ledger: (propertyId: number) =>
    queryOptions({
      queryKey: [...propertyQueries.all(), 'ledger', propertyId] as const,
      queryFn: () => fetchBuildingLedger(propertyId),
    }),
};
