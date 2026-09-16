// 금액 · 비율 · 일시 표기. 컴포넌트에서 toLocaleString · Intl을 직접 쓰지 않고 여기를 쓴다.
// 받은 값(원 단위 정수 · 수치 비율 · ISO 8601 문자열)은 그대로 두고 표기만 바꾼다 — 공통 규약 1.1.
// 보증금 억 단위 축약 형식(1억 이상 소수 1자리 · 1억 미만 만 단위)은 첫 지도 화면 계획에서 정했다 — formatDepositShort.

const TIME_ZONE = 'Asia/Seoul';

/** 축약 단위 — 만(10^4) · 억(10^8) */
const MAN = 10_000;
const EOK = 100_000_000;

const wonFormatter = new Intl.NumberFormat('ko-KR');
const percentFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 });
/** 억 단위 축약 — 소수 1자리 반올림. 소수부가 0이면 생략된다 (2 → `2`, 12.34 → `12.3`) */
const eokFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 });
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

/**
 * 보증금 축약 표기 — 마커에 들어가는 짧은 형태 (매물 API 명세 1.4 「마커에 억 단위로 축약 표기한다」).
 * 1억 이상은 억 단위 소수 1자리(`2.3억` · `2억`), 1억 미만 1만 이상은 만 단위 정수(`9,500만`),
 * 1만 미만은 원 단위(`9,999원` · `0원`). 소수는 버리지 않고 반올림한다.
 * 반올림한 만 단위가 1억에 닿으면 억으로 올려 적는다 — `10,000만`을 만들지 않는다.
 */
export function formatDepositShort(won: number): string {
  if (won < MAN) return formatWon(won);
  const man = Math.round(won / MAN);
  if (man < EOK / MAN) return `${wonFormatter.format(man)}만`;
  return `${eokFormatter.format(won / EOK)}억`;
}

/** 백분율 수치(명세의 비율은 68.0 꼴) → `68%` · `116.7%` */
export function formatPercent(ratio: number): string {
  return `${percentFormatter.format(ratio)}%`;
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

function toDate(isoDateTime: string): Date | null {
  const date = new Date(isoDateTime);
  return Number.isNaN(date.getTime()) ? null : date;
}
