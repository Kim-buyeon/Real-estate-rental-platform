// 대출 쿼리 정의. 쿼리 키가 만들어지는 유일한 곳이다 — frontend/CLAUDE.md 쿼리.
import { queryOptions } from '@tanstack/react-query';
import { fetchLoanLimit } from '../api/loan';

export const loanQueries = {
  /**
   * 도메인 루트. 무효화 연쇄(프로필 수정 성공 → loan 전체)가 이 키로 걸린다 —
   * 자격 정보가 한도 계산의 입력이라 매물마다 따로 잡힌 한도가 한꺼번에 낡는다 (queries/user.ts).
   */
  all: () => ['loan'] as const,

  /**
   * LOAN-01 한도 계산. 인증 「필수」라 비로그인에서는 호출하지 않는다 — 부르는 쪽이 enabled로 정한다
   * (features/loan/components/LoanLimitSection.tsx).
   *
   * 보증보험 가입 불가(422 LOAN_PROPERTY_NOT_ELIGIBLE)와 자격 정보 미입력(422 PROFILE_INCOMPLETE)은
   * 오류 응답이지만 화면에서는 안내로 가른다. 재시도는 여기서 끄지 않는다 — app/queryClient.ts의
   * 기본값이 4xx를 이미 재시도하지 않는다 (queries/risk.ts의 미분석 404와 같은 판단이다).
   */
  limit: (propertyId: number) =>
    queryOptions({
      queryKey: [...loanQueries.all(), 'limit', propertyId] as const,
      queryFn: () => fetchLoanLimit(propertyId),
    }),
};
