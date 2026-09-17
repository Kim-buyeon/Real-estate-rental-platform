// 위험도 분석 API 명세 — 명세 표의 행 하나 = 함수 하나. 이번 범위는 위험도 조회(RISK-01 · RISK-05) 하나다.
// 등기 이력(RISK-07) · 재분석(RISK-08) 함수는 그 슬라이스가 이 파일에 추가한다.
import type {
  GradeReason,
  GuaranteeFailedCondition,
  GuaranteeProvider,
  OwnershipRightType,
  PersonalCondition,
  PriceType,
  RiskGrade,
} from '../domain/risk';
import { request } from './client';

/** 기관별 가입 판정 — 명세 1.1 providers[]. HUG · HF · SGI 셋이 항상 온다 */
export interface InsuranceProvider {
  provider: GuaranteeProvider;
  eligible: boolean;
  /** 위배된 집 단위 조건. 위배를 모두 담는다 */
  failedConditions: GuaranteeFailedCondition[];
  /** HF 고유의 전세자금보증부 대출 연계 필요 표기. 가입 불가 사유가 아니다 */
  loanLinkRequired: boolean;
  /** 보증한도 (원) — 주택가격 × 담보인정비율 − 선순위채권. 서버가 계산한 값을 표시만 한다 */
  guaranteeLimit: number;
  /** 예상 보증료 (원). 가입 불가면 null */
  estimatedPremium: number | null;
  /** 가입 가능한 보증 상품명. 가입 불가면 null */
  productName: string | null;
}

/** 명의 · 문서 정합 확인 결과 (RISK-04) — 명세 1.1 consistency */
export interface RiskConsistency {
  ownerNameMatched: boolean;
  addressMatched: boolean;
  violationBuilding: boolean;
  areaMatched: boolean;
}

/** 위험 등급과 판정 근거 — 명세 1.1. 깡통전세 · 권리 침해 · 정합 · 3사 판정이 모두 이 응답에 담긴다 */
export interface RiskAnalysis {
  riskGrade: RiskGrade;
  gradeReason: GradeReason;
  /** 전세가율 (%) */
  debtRatio: number;
  /** 적용 시세 (원) */
  marketPrice: number;
  priceType: PriceType;
  /** 시세 기준일 (YYYY-MM-DD) */
  priceDate: string;
  /** 선순위채권 합계 (원) */
  seniorDebtTotal: number;
  /** 깡통전세 해당 여부. 기준 비율은 서버(RISK_CRITERIA)가 갖는다 */
  isNegativeEquity: boolean;
  /** 3사 중 하나 이상 가입 가능 여부 */
  insuranceEligible: boolean;
  providers: InsuranceProvider[];
  /** 시스템이 판정하지 않는 개인 자격 확인 사항 */
  personalConditions: PersonalCondition[];
  /** 판정에 반영된 권리 침해 항목 (압류 · 가압류 · 경매개시결정 · 신탁) */
  rightViolations: OwnershipRightType[];
  /** 판정에 반영되지 않는 경고 항목 (가등기 · 임차권등기명령 등) */
  warnings: OwnershipRightType[];
  consistency: RiskConsistency;
  /** 분석 기준 시각 (ISO 8601) */
  analyzedAt: string;
}

/**
 * RISK-01 · RISK-05 · GET /api/properties/{propertyId}/risk — 인증 선택.
 * 분석 이력이 없는 매물은 404 RISK_NOT_ANALYZED로 온다. 오류가 아니라 정상 상태이며,
 * 그 처리는 쿼리 정의(queries/risk.ts)와 화면이 한다 — 여기서는 ApiError가 그대로 던져진다.
 */
export const fetchRiskAnalysis = (propertyId: number) =>
  request<RiskAnalysis>({ url: `/properties/${propertyId}/risk` });
