// 위험 등급 열거값 · 표시 문구 · 색 토큰. 등급에서 시각 요소로 가는 매핑은 전부 여기다
// (frontend/CLAUDE.md 타입·열거값). 값은 매물 API 명세 1.4 · 위험도 명세와 백엔드 RiskGrade 그대로다.

export const RISK_GRADES = ['SAFE', 'CAUTION', 'DANGER'] as const;
export type RiskGrade = (typeof RISK_GRADES)[number];

export const RISK_GRADE_LABEL: Record<RiskGrade, string> = {
  SAFE: '안전',
  CAUTION: '주의',
  DANGER: '위험',
};

/** 색 값(hex)은 여기 없다 — tokens.css의 --color-risk-* 와 Badge 변형 이름이 갖는다 */
export const RISK_GRADE_TOKEN: Record<RiskGrade, 'risk-safe' | 'risk-caution' | 'risk-danger'> = {
  SAFE: 'risk-safe',
  CAUTION: 'risk-caution',
  DANGER: 'risk-danger',
};

/** 미분석 — 분석 이력이 없어 riskGrade가 null인 상태 (명세 1.4 마지막 줄). 열거값이 아니므로 매핑 밖에 둔다 */
export const UNANALYZED_LABEL = '미분석';
export const UNANALYZED_TOKEN = 'risk-unanalyzed';

/** null(미분석) 처리는 여기서 한다 — 컴포넌트마다 `grade ?? …`를 적지 않는다 */
export const riskGradeLabel = (grade: RiskGrade | null) => (grade ? RISK_GRADE_LABEL[grade] : UNANALYZED_LABEL);
export const riskGradeToken = (grade: RiskGrade | null) => (grade ? RISK_GRADE_TOKEN[grade] : UNANALYZED_TOKEN);
