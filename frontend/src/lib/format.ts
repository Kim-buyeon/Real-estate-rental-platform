// 금액 · 비율 · 일시 표기. 컴포넌트에서 toLocaleString · Intl을 직접 쓰지 않고 여기를 쓴다.
// 받은 값(원 단위 정수 · 수치 비율 · ISO 8601 문자열)은 그대로 두고 표기만 바꾼다 — 공통 규약 1.1.
// 보증금 억 단위 축약은 첫 지도 화면 계획에서 정해 여기에 추가한다.

const TIME_ZONE = 'Asia/Seoul';

const wonFormatter = new Intl.NumberFormat('ko-KR');
const percentFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 });
const dateTimeFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: TIME_ZONE,
  dateStyle: 'medium',
  timeStyle: 'short',
});
const dateFormatter = new Intl.DateTimeFormat('ko-KR', { timeZone: TIME_ZONE, dateStyle: 'medium' });

/** 원 단위 정수 → `150,000,000원` */
export function formatWon(amount: number): string {
  return `${wonFormatter.format(amount)}원`;
}

/** 백분율 수치(명세의 비율은 68.0 꼴) → `68%` · `116.7%` */
export function formatPercent(ratio: number): string {
  return `${percentFormatter.format(ratio)}%`;
}

function toDate(isoDateTime: string): Date | null {
  const date = new Date(isoDateTime);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** ISO 8601 → 서울 시각 `2026. 7. 29. 오후 12:10`. 해석할 수 없으면 받은 문자열 그대로 */
export function formatDateTime(isoDateTime: string): string {
  const date = toDate(isoDateTime);
  return date ? dateTimeFormatter.format(date) : isoDateTime;
}

/** ISO 8601 → 서울 날짜 `2026. 7. 29.`. 해석할 수 없으면 받은 문자열 그대로 */
export function formatDate(isoDateTime: string): string {
  const date = toDate(isoDateTime);
  return date ? dateFormatter.format(date) : isoDateTime;
}
