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

// ── 구독 설정 (NOTI-01) ──────────────────────────────────────────────────
// 위의 알림 유형(NOTIFICATION_TYPES)과 다른 열거다. 유형은 「받은 알림이 무엇인가」이고
// 이것은 「무엇을 받을 것인가」다 — 값도 명세 1.2의 응답 키(newProperty · rateChange ·
// wishlistMonitoring · consultSchedule) 그대로이며 둘을 합치지 않는다.

export const SUBSCRIPTION_ITEMS = ['newProperty', 'rateChange', 'wishlistMonitoring', 'consultSchedule'] as const;
export type SubscriptionItem = (typeof SUBSCRIPTION_ITEMS)[number];

/** 항목 문구. 명세 1.2의 「신규 매물 · 금리 변동 · 관심 매물 모니터링 · 상담 일정」을 따른다 */
export const SUBSCRIPTION_ITEM_LABEL: Record<SubscriptionItem, string> = {
  newProperty: '신규 매물',
  rateChange: '금리 변동',
  wishlistMonitoring: '관심 매물 모니터링',
  consultSchedule: '상담 일정',
};

/**
 * 항목 안내. 명세가 동작을 정해 둔 것만 적는다 — 없는 항목은 문구를 지어내지 않는다.
 * 관심 매물 모니터링은 켜고 끄는 범위가 이 화면 밖까지 미쳐(등록된 관심 매물 전체 + 앞으로 등록할 것)
 * 모르면 예상 밖의 결과가 되므로 반드시 보여준다 (명세 1.2 마지막 줄).
 */
export const SUBSCRIPTION_ITEM_HINT: Partial<Record<SubscriptionItem, string>> = {
  wishlistMonitoring: '등록된 관심 매물 전체에 일괄 반영되고, 이후 등록하는 관심 매물도 이 값을 따릅니다.',
};

/** 수신 여부 문구. Select의 두 선택지이며 켬 · 끔을 조건 분기로 적지 않는다 */
export const SUBSCRIPTION_ENABLED_LABEL: Record<'true' | 'false', string> = {
  true: '수신',
  false: '수신 안 함',
};
