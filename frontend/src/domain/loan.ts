// 대출 도메인 열거값 · 표시 문구. 값은 대출 API 명세 1.1과 백엔드 AppliedRegulation 그대로다
// (frontend/CLAUDE.md 타입·열거값). 기준값 · 금액 · 임계 수치는 여기에 두지 않는다 —
// 보증금 비율 · 보증기관 상한 · DSR 비율 · 스트레스 금리는 서버(LOAN_REGULATION)와 판정 기준 문서가 갖는다.

/**
 * 최종 한도를 결정한 항목 — 명세 1.1 appliedRegulation. 선언 순서가 동률일 때의 우선순위이기도 하다
 * (명세 「같은 값이면 이 순서의 앞 항목」). 어느 항목이 결정했는지는 서버가 정해 보내고 화면은 표시만 한다.
 */
export const APPLIED_REGULATIONS = ['DEPOSIT_RATIO', 'GUARANTEE_CAP', 'DSR', 'PRODUCT_LIMIT'] as const;
export type AppliedRegulation = (typeof APPLIED_REGULATIONS)[number];

/** 문구는 명세 1.1 필드 설명 · 비즈니스 로직 6장 한도 항목 표와 같은 말을 쓴다 — 한 항목이 두 이름으로 불리지 않게 */
export const APPLIED_REGULATION_LABEL: Record<AppliedRegulation, string> = {
  DEPOSIT_RATIO: '보증금 기준 한도',
  GUARANTEE_CAP: '보증기관 상한',
  DSR: 'DSR 기준 한도',
  PRODUCT_LIMIT: '상품 한도',
};

/**
 * 스트레스 금리 적용 한도(명세 1.1 stressDsrLimit)의 문구. 한도 항목이 아니라 참고 수치라
 * APPLIED_REGULATION_LABEL 밖에 둔다 — appliedRegulation이 이 값으로 오는 일은 없다.
 */
export const STRESS_DSR_LABEL = '스트레스 금리 적용 한도';

/** 참고용 총부채상환비율(명세 1.1 dtiReference) */
export const DTI_REFERENCE_LABEL = 'DTI(참고)';

/**
 * 보증보험 가입이 불가한 매물 — 명세 1.1 · 공통 규약 2장. 이 코드일 때만 오류가 아니라 정상 안내로 가른다.
 * 「보증보험 가입이 불가한 매물에는 한도를 제시하지 않는다」(기능 정의 LOAN-01)가 설계이지 실패가 아니다.
 * 문구는 서버 error.message 그대로 쓴다.
 */
export const LOAN_PROPERTY_NOT_ELIGIBLE = 'LOAN_PROPERTY_NOT_ELIGIBLE';

/**
 * 계산에 필요한 자격 정보가 없는 상태 — 명세 1.1 · 공통 규약 1.2. 오류 봉투의 field에 미입력 항목이 온다.
 * 사용자가 고칠 수 있는 상태이고 고칠 화면(USER-03)이 이미 있어 오류가 아니라 안내로 가른다.
 */
export const PROFILE_INCOMPLETE = 'PROFILE_INCOMPLETE';

// 서버가 우리가 모르는 코드를 보내도 화면이 빈칸이 되지 않게 코드 문자열을 그대로 보여준다 —
// domain/risk.ts의 labelOf와 같은 방식이다
function labelOf(labels: Record<string, string>, code: string): string {
  return labels[code] ?? code;
}

/** 파라미터를 string으로 받는 이유는 domain/risk.ts와 같다 — 모르는 코드를 그대로 돌려주는 것이 이 함수의 일이다 */
export const appliedRegulationLabel = (regulation: string) => labelOf(APPLIED_REGULATION_LABEL, regulation);
