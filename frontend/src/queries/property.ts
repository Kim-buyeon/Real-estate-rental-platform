// 매물 쿼리 정의. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
// 관심 매물(PROP-05)은 매물 폴더에 있지만 쿼리 루트를 따로 둔다 — 무효화 범위가 다르다.
import {
  infiniteQueryOptions,
  keepPreviousData,
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import type { ApiError } from '../api/client';
import {
  addWishlist,
  fetchBuildingLedger,
  fetchDistrictCounts,
  fetchPropertyDetail,
  fetchPropertyList,
  fetchPropertyMarkers,
  fetchWishlist,
  removeWishlist,
  type BoundingBox,
  type PropertyFilter,
} from '../api/property';
import type { PropertySort } from '../domain/property';

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

  /**
   * PROP-01 매물 목록 — 지도 옆 패널의 목록 탭. 커서 목록이라 infiniteQueryOptions다
   * (공통 규약 1.4 — 응답의 nextCursor를 그대로 다음 요청에 넣는다).
   *
   * 필터와 정렬이 요청을 바꾸므로 둘 다 키에 들어간다. 없으면 다른 조건의 결과가 캐시에서 나오고,
   * 정렬을 바꿔도 앞의 순서가 그대로 보인다. 커서는 pageParam으로 TanStack Query가 관리한다.
   * 좌표는 들어가지 않는다 — 목록 조회는 좌표를 보내지 않는다 (명세 1.3, api/property.ts).
   */
  list: (filter: PropertyFilter, sort?: PropertySort) =>
    infiniteQueryOptions({
      queryKey: [...propertyQueries.all(), 'list', filter, sort] as const,
      queryFn: ({ pageParam }) => fetchPropertyList(filter, sort, pageParam),
      initialPageParam: undefined as string | undefined, // v5는 필수
      getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.nextCursor : undefined),
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

export const wishlistQueries = {
  /** 도메인 루트. 등록 · 해제와 등급이 바뀐 재분석 · SSE 위험도 변경이 이 키로 걸린다 */
  all: () => ['wishlist'] as const,

  /**
   * PROP-05 관심 매물 목록. 커서 목록이라 infiniteQueryOptions다 — 응답의 nextCursor를 그대로
   * 다음 요청에 넣는다 (공통 규약 1.4). 조건이 없어 키에 들어갈 값도 커서뿐이며, 커서는
   * pageParam으로 TanStack Query가 관리한다.
   */
  list: () =>
    infiniteQueryOptions({
      queryKey: [...wishlistQueries.all(), 'list'] as const,
      queryFn: ({ pageParam }) => fetchWishlist(pageParam),
      initialPageParam: undefined as string | undefined, // v5는 필수
      getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.nextCursor : undefined),
    }),
};

/**
 * 등록 · 해제가 같은 것을 낡게 만든다 — 관심 매물 목록 전체와 그 매물의 상세(wishlisted가 바뀐다).
 * 무효화 연쇄 표의 「관심 매물 등록 · 해제 성공」 한 행이므로 두 훅이 같은 함수를 쓴다.
 *
 * 무효화 프로미스를 돌려준다 — v5는 onSuccess가 돌려준 프로미스를 기다린 뒤에야 뮤테이션을 성공으로
 * 바꾸므로, 버튼의 로딩 표시가 「재조회된 wishlisted가 캐시에 들어올 때까지」 이어진다. 기다리지
 * 않으면 등록 성공과 상세 재조회 사이에 버튼이 잠시 「등록」으로 돌아와 한 번 더 눌리고, 그 두 번째
 * 요청이 409 WISHLIST_DUPLICATED가 된다.
 */
const invalidateAfterWishlistChange = (queryClient: QueryClient, propertyId: number) =>
  Promise.all([
    queryClient.invalidateQueries({ queryKey: wishlistQueries.all() }),
    queryClient.invalidateQueries({ queryKey: propertyQueries.detail(propertyId).queryKey }),
  ]);

// 오류 타입을 ApiError로 둔다 — client가 어떤 실패든 ApiError로 바꿔 던진다.
// 화면은 error.message를 그대로 보여준다(409 WISHLIST_DUPLICATED 포함).

/** PROP-05 관심 매물 등록. 변수는 propertyId다 */
export function useAddWishlist() {
  const queryClient = useQueryClient();
  return useMutation<null, ApiError, number>({
    mutationFn: addWishlist,
    onSuccess: (_, propertyId) => invalidateAfterWishlistChange(queryClient, propertyId),
  });
}

/** PROP-05 관심 매물 해제. 묻지 않고 바로 해제한다 — 되돌리기가 한 번 더 누르는 것이다 (이슈 #96 계획) */
export function useRemoveWishlist() {
  const queryClient = useQueryClient();
  return useMutation<null, ApiError, number>({
    mutationFn: removeWishlist,
    onSuccess: (_, propertyId) => invalidateAfterWishlistChange(queryClient, propertyId),
  });
}
