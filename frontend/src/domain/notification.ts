// 알림 유형 열거값과 표시 문구. 값은 알림 API 명세 1.3 · 1.4 그대로다.
// 유형 목록은 SSE 이벤트 이름이기도 하다 — app/NotificationStream.tsx가 이 상수로 리스너를 건다
// (명세 1.4 「이벤트 이름은 알림 유형과 동일하게 지정한다」).
import { RISK_GRADES, riskGradeLabel, type RiskGrade } from './risk';

export const NOTIFICATION_TYPES = ['RISK_CHANGE', 'REGISTRY_CHANGE'] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

/**
 * 유형 문구. 알림의 본문 문구(title)는 서버가 유형별 고정 문구로 주므로 여기에 다시 적지 않는다 —
 * 두 곳에 적으면 한쪽만 고쳐진다. 여기 있는 것은 목록에서 유형을 가르는 짧은 이름이다.
 */
export const NOTIFICATION_TYPE_LABEL: Record<NotificationType, string> = {
  RISK_CHANGE: '위험 등급 변경',
  REGISTRY_CHANGE: '등기 변동',
};

/**
 * beforeValue · afterValue 표기. 유형마다 값의 의미가 다르다 (명세 1.3) —
 * RISK_CHANGE는 위험 등급 상수명이라 등급 문구 매핑(domain/risk.ts)을 거치고,
 * REGISTRY_CHANGE는 갑구 · 을구 건수와 내용 지문 요약 문자열이라 서버 값을 그대로 보여준다.
 * 컴포넌트가 유형으로 분기해 문구를 만들지 않게 여기서 갈라 준다.
 */
export function notificationValueLabel(type: string, value: string): string {
  if (type === 'RISK_CHANGE' && isRiskGrade(value)) return riskGradeLabel(value);
  return value;
}

// ── 표시 문구 헬퍼 ────────────────────────────────────────────────────────
// 서버가 우리가 모르는 유형을 보내도 화면이 빈칸이 되지 않게 코드 문자열을 그대로 보여준다 —
// domain/risk.ts와 같은 방식이다. 파라미터를 string으로 받는 이유도 같다.

const labelOf = (labels: Record<string, string>, code: string): string => labels[code] ?? code;

export const notificationTypeLabel = (type: string) => labelOf(NOTIFICATION_TYPE_LABEL, type);

const isRiskGrade = (value: string): value is RiskGrade =>
  (RISK_GRADES as readonly string[]).includes(value);
