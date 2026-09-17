// 위험도 분석 API 명세 — 명세 표의 행 하나 = 함수 하나. 위험도 조회(RISK-01 · RISK-05) ·
// 등기 이력(RISK-07) · 재분석(RISK-08) 셋이다. 보증 신청기한 계산(RISK-06)은 부가 기능이라 아직 없다.
import type {
  GradeReason,
  GuaranteeFailedCondition,
  GuaranteeProvider,
  OwnershipRightType,
  PersonalCondition,
  PriceType,
  RegistryDataSource,
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

// ── 등기 이력 (RISK-07) ──────────────────────────────────────────────────
// 판정 근거가 아니라 원본 자료다. 위험도 응답에는 판정에 쓰인 결론(선순위채권 합계 · 권리 침해 ·
// 경고)만 담기고 건별 상세는 이쪽에 있다 — 명세 1장.

/** 갑구 소유권 변동 — 명세 1.3 ownerships[] */
export interface OwnershipRecord {
  /** 갑구 순위번호 */
  rankNo: number;
  rightType: OwnershipRightType;
  holderName: string;
  /** 접수일 (YYYY-MM-DD). 이 날짜가 우선변제 순서를 정한다 */
  receivedDate: string;
  cause: string;
  /** 말소되지 않은 등기 여부 */
  isActive: boolean;
}

/** 을구 근저당 — 명세 1.3 mortgages[] */
export interface MortgageRecord {
  /** 을구 순위번호 */
  rankNo: number;
  creditor: string;
  /** 채권최고액 (원) */
  maxClaimAmount: number;
  receivedDate: string;
  isActive: boolean;
}

/**
 * 등기 갑구 · 을구 이력 — 명세 1.3. 두 배열 모두 접수일 오름차순, 같으면 순위번호 순으로 온다.
 * 화면에서 다시 정렬하지 않는다.
 */
export interface Registry {
  propertyId: number;
  ownerships: OwnershipRecord[];
  mortgages: MortgageRecord[];
  /** 등기 수집 시각 (ISO 8601) */
  collectedAt: string;
  /** 유료 중계 연동 전까지 MOCK 하나다 */
  dataSource: RegistryDataSource;
}

/**
 * RISK-07 · GET /api/properties/{propertyId}/registry — 인증 선택.
 * 「매물마다 건수 편차가 크므로 별도 엔드포인트로 분리한다」(명세 1장) — 상세 진입 시 부르지 않고
 * 패널에서 펼칠 때 부른다.
 */
export const fetchRegistry = (propertyId: number) =>
  request<Registry>({ url: `/properties/${propertyId}/registry` });

// ── 재분석 (RISK-08) ─────────────────────────────────────────────────────

/**
 * 재분석 결과 — 명세 1.4. previousGrade는 요청 시점의 최신 분석 등급이고 분석된 적이 없으면 null,
 * gradeChanged는 previousGrade가 있고 riskGrade와 다를 때만 참이다(첫 분석이면 거짓) — 명세 1.2.
 */
export interface RiskReanalyzeResult {
  propertyId: number;
  previousGrade: RiskGrade | null;
  riskGrade: RiskGrade;
  gradeChanged: boolean;
  /** 분석 기준 시각 (ISO 8601) */
  analyzedAt: string;
}

/**
 * RISK-08 · POST /api/properties/{propertyId}/risk/reanalyze — 인증 **필수**.
 *
 * 같은 매물에 최소 간격이 걸린다. 간격 안의 재요청은 429 RISK_REANALYZE_TOO_SOON이고 다음 요청
 * 가능 시각이 오류 봉투의 retryAfter로 온다 — 명세 1.2 · 공통 규약 1.2. 간격은 매물 단위이므로
 * 요청한 사용자와 무관하고, 간격 값 자체는 서버 설정이며 미확정이다(5주차 확정). 화면은 그 값을
 * 알지 못하고 서버가 준 시각만 표시한다.
 */
export const reanalyzeRisk = (propertyId: number) =>
  request<RiskReanalyzeResult>({ method: 'POST', url: `/properties/${propertyId}/risk/reanalyze` });
