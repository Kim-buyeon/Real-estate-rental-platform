// 위험도 쿼리 정의. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
// 등기 이력(registry) 정의와 재분석 뮤테이션 훅은 그 슬라이스가 여기에 추가한다.
import { queryOptions } from '@tanstack/react-query';
import { fetchRiskAnalysis } from '../api/risk';

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
};
