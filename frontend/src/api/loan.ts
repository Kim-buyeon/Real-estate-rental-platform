// 대출 API 명세 — 명세 표의 행 하나 = 함수 하나. 1단계 범위는 LOAN-01 한도 계산 하나다.
// LOAN-02 정책상품 자격 · LOAN-03 추천 · LOAN-04 시뮬레이션 · LOAN-05 계획 저장 · LOAN-06 금리 이력은
// 부가 · 차기 범위라 함수를 만들지 않는다 (frontend/CLAUDE.md API 함수).
import type { AppliedRegulation } from '../domain/loan';
import { request } from './client';

/**
 * GET /api/loans/limit 응답 — 명세 1.1. 여덟 필드가 명세 표 그대로다.
 *
 * 값은 서버가 계산한 결과이며 화면은 표시만 한다 — 최솟값 · 결정 항목 · DTI를 다시 계산하지 않는다
 * (frontend/CLAUDE.md 상태 「화면에서 판정하지 않는다」).
 */
export interface LoanLimit {
  /** 보증금 기준 한도 (원) */
  depositLimit: number;
  /** 보증기관 상한 (원) — 무주택 · 주택 보유 구분은 서버가 한다 */
  guaranteeCapLimit: number;
  /** DSR 기준 한도 (원). 주택 보유자만 계산되며 무주택이면 null */
  dsrLimit: number | null;
  /** 스트레스 금리 적용 한도 (원) — 참고이며 최종 한도에 미반영. 무주택이면 null */
  stressDsrLimit: number | null;
  /** 위 항목과 상품 한도 중 최솟값 (원) */
  finalLimit: number;
  /** 최종 한도를 결정한 항목 */
  appliedRegulation: AppliedRegulation;
  /** 참고용 총부채상환비율 (%). 한도 판정에 쓰지 않으며 연소득이 0이면 null */
  dtiReference: number | null;
  /** 계산에 필요하나 미입력된 자격 정보 항목. 성공 응답에서는 빈 배열이다 */
  missingFields: string[];
}

export const fetchLoanLimit = (propertyId: number) =>
  request<LoanLimit>({ url: '/loans/limit', params: { propertyId } });
