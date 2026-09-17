// 위험도 쿼리 정의와 뮤테이션 훅. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../api/client';
import { fetchRegistry, fetchRiskAnalysis, reanalyzeRisk, type RiskReanalyzeResult } from '../api/risk';
import { propertyQueries } from './property';

export const riskQueries = {
  /** 도메인 루트. 무효화 연쇄(재분석 · SSE 위험도 변경)가 이 키로 걸린다 */
  all: () => ['risk'] as const,

  /**
   * RISK-01 · RISK-05 판정 근거 조회.
   *
   * 미분석 매물은 404 RISK_NOT_ANALYZED가 정상 응답이라 재시도하면 같은 404를 세 번 더 받는다.
   * 재시도는 여기서 끄지 않는다 — app/queryClient.ts의 기본값이 4xx를 이미 재시도하지 않는다.
   * 그 기본값이 바뀌면 이 정의에 retry 예외를 둔다.
   */
  analysis: (propertyId: number) =>
    queryOptions({
      queryKey: [...riskQueries.all(), 'analysis', propertyId] as const,
      queryFn: () => fetchRiskAnalysis(propertyId),
    }),

  /**
   * RISK-07 등기 이력. 상세 진입 시 부르지 않고 패널에서 펼칠 때 부른다 — 화면이 닫혀 있는 동안
   * 이 컴포넌트를 마운트하지 않는 것으로 그 시점을 정하고, 여기서는 키와 함수만 둔다
   * (매물 명세 1.4 「탐색 동작과 호출 시점」).
   */
  registry: (propertyId: number) =>
    queryOptions({
      queryKey: [...riskQueries.all(), 'registry', propertyId] as const,
      queryFn: () => fetchRegistry(propertyId),
    }),
};

/**
 * RISK-08 재분석 요청. 성공하면 그 매물의 위험도 · 상세 · 등기를 무효화한다 —
 * 등급 · 전세가율 · 판정 근거가 바뀌고(위험도 · 상세의 riskSummary), 등기를 다시 뗐으므로
 * 이력도 낡는다 (frontend/CLAUDE.md 무효화 연쇄).
 *
 * 등급이 바뀐 경우(gradeChanged)에는 그 매물 밖까지 낡는다 — 지도 마커 색과 자치구 gradeCounts가
 * 이전 등급이라 패널만 새 등급을 그리면 한 화면에 두 등급이 보인다. 그래서 property 전체다.
 *
 * 재시도는 여기서 끄지 않는다 — app/queryClient.ts의 기본값은 쿼리에만 걸리고 뮤테이션의 기본
 * 재시도는 0이다. 429를 거듭 치지 않는다.
 */
export function useReanalyzeRisk(propertyId: number) {
  const queryClient = useQueryClient();
  return useMutation<RiskReanalyzeResult, ApiError, void>({
    mutationFn: () => reanalyzeRisk(propertyId),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: riskQueries.analysis(propertyId).queryKey });
      void queryClient.invalidateQueries({ queryKey: riskQueries.registry(propertyId).queryKey });
      void queryClient.invalidateQueries({ queryKey: propertyQueries.detail(propertyId).queryKey });

      if (result.gradeChanged) {
        void queryClient.invalidateQueries({ queryKey: propertyQueries.all() });
        // 무효화 연쇄 표는 여기에 wishlist 전체도 정한다 — 관심 매물 목록의 previousGrade가 바뀐다.
        // wishlistQueries가 아직 없어(PROP-05) 그 슬라이스가 이 자리에 한 줄을 더한다.
      }
    },
  });
}
