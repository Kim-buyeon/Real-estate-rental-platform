// 알림 API 명세 — 명세 표의 행 하나 = 함수 하나. 여기 있는 것은 NOTI-05 세 행(목록 · 개별 읽음 ·
// 전체 읽음)과 NOTI-01 두 행(구독 설정 조회 · 수정)이다. NOTI-04 푸시 토큰은 차기 · 조건부 범위라
// 함수를 만들지 않는다 (frontend/CLAUDE.md API 함수).
// NOTI-03은 두 행이다 — 스트림 티켓 발급은 request<T>()로 여기에 있고, 실시간 수신(SSE) 행은
// request<T>()가 아니라 EventSource이며 app/NotificationStream.tsx가 갖는다.
import type { NotificationType } from '../domain/notification';
import type { ContractType } from '../domain/property';
import { request } from './client';
import type { CursorPage } from './types';

/**
 * 알림 목록의 항목 — 명세 1.3 응답 예시 그대로. 필드를 더하거나 이름을 바꾸지 않는다.
 */
export interface Notification {
  notificationId: number;
  type: NotificationType;
  /** 유형별 고정 문구. 서버가 준 문구를 그대로 쓴다 — 프론트가 다시 적지 않는다 (명세 1.3) */
  title: string;
  /** 알림이 가리키는 매물. 관심 매물을 해제한 뒤에도 남는다 */
  propertyId: number;
  /**
   * 변동 전 · 후 값. 유형마다 의미가 다르다 — RISK_CHANGE는 위험 등급 상수명,
   * REGISTRY_CHANGE는 갑구 · 을구 유효 건수와 내용 지문 요약 문자열이다 (명세 1.3).
   * 표기는 domain/notification.ts의 notificationValueLabel이 갈라 준다.
   */
  beforeValue: string;
  afterValue: string;
  isRead: boolean;
  /** ISO 8601 · Asia/Seoul */
  createdAt: string;
}

/**
 * 알림 목록 응답 — 공통 규약 1.4의 커서 페이지에 unreadCount가 하나 더 붙는다 (명세 1.3).
 * 이 값은 페이지와 무관한 이 사용자의 읽지 않은 알림 전체 수이며, 목록의 건수가 아니다 —
 * 화면은 이 값을 그대로 쓰고 items에서 세지 않는다.
 */
export interface NotificationPage extends CursorPage<Notification> {
  unreadCount: number;
}

/**
 * 스트림 티켓 발급 응답 — 명세 1.1. 짧은 수명의 일회용 티켓 문자열 하나다.
 * 만료 시각 · 수명을 필드로 받지 않는다 — 명세에 없는 필드를 만들지 않으며, 티켓은 받는 즉시
 * 연결에 쓰므로 프론트가 수명을 알 이유가 없다.
 */
export interface NotificationStreamTicket {
  ticket: string;
}

/**
 * NOTI-03 · POST /api/notifications/stream-ticket — 인증 필수. 연결 하나마다 티켓 하나를 발급받는다.
 *
 * 표준 EventSource는 헤더를 붙일 수 없어 연결 자격을 URL로 넘겨야 하는데, 액세스 토큰을 URL에 담는
 * 것은 RFC 9700이 금지하는 방식이다(이슈 102) — 그래서 URL에 실리는 값을 짧은 수명의 일회용
 * 티켓으로 바꿨다. 발급은 Bearer 헤더를 붙일 수 있는 보통의 POST다.
 *
 * **받은 티켓을 저장하지 않는다.** 호출자는 URL에 쓰고 버린다 (app/NotificationStream.tsx).
 */
export const issueStreamTicket = () =>
  request<NotificationStreamTicket>({ method: 'POST', url: '/notifications/stream-ticket' });

/**
 * NOTI-05 · GET /api/notifications — 인증 필수. 커서 목록이다 (공통 규약 1.4).
 * size는 보내지 않는다 — 기본값 20을 서버가 쓴다. 읽은 알림도 함께 온다 (명세 1.3).
 */
export const fetchNotifications = (cursor?: string) =>
  request<NotificationPage>({ url: '/notifications', params: { cursor } });

/**
 * NOTI-05 · PATCH /api/notifications/{notificationId}/read — 이미 읽은 알림도 200이다.
 * 없거나 다른 사용자의 알림이면 404 NOTIFICATION_NOT_FOUND이며 문구는 서버 error.message 그대로다.
 * 응답 본문의 data는 null이다 (명세 1.4 마지막 줄).
 */
export const markNotificationRead = (notificationId: number) =>
  request<null>({ method: 'PATCH', url: `/notifications/${notificationId}/read` });

/** NOTI-05 · PATCH /api/notifications/read-all — 바꿀 알림이 없어도 200이다 (명세 1.3) */
export const markAllNotificationsRead = () =>
  request<null>({ method: 'PATCH', url: '/notifications/read-all' });

/**
 * 신규 매물 구독 조건 — 명세 1.2. **조건은 신규 매물만 갖는다.** 금리 변동 · 관심 매물 모니터링 ·
 * 상담 일정은 수신 여부만 갖는다.
 *
 * contractType · depositMax는 선택이라 요청에서 생략할 수 있고, 조회 응답은 설정이 없으면 null을
 * 준다(명세 1.2 「조건이 없으면 districts는 빈 배열, contractType · depositMax는 null」) —
 * 그래서 선택 + null 허용 둘 다다.
 */
export interface NewPropertyConditions {
  /** 서울 자치구명(「강서구」). 중복 없음, 최대 25개. enabled가 true면 1개 이상 — 검증은 서버가 한다 */
  districts: string[];
  /** 생략하면 계약 유형 전체 */
  contractType?: ContractType | null;
  /** 원. 생략하면 상한 없음 */
  depositMax?: number | null;
}

/** 수신 여부만 갖는 항목 — 금리 변동 · 관심 매물 모니터링 · 상담 일정 */
export interface NotificationSubscription {
  enabled: boolean;
}

/**
 * NOTI-01 구독 설정 — 명세 1.2 응답 예시 그대로의 네 항목. 조회와 수정이 같은 구조다.
 * 설정한 적 없으면 wishlistMonitoring만 true이고 나머지는 false다 (명세 1.2).
 */
export interface NotificationSubscriptions {
  newProperty: NotificationSubscription & { conditions: NewPropertyConditions };
  rateChange: NotificationSubscription;
  wishlistMonitoring: NotificationSubscription;
  consultSchedule: NotificationSubscription;
}

/**
 * NOTI-01 · GET /api/me/notification-subscriptions — 인증 필수.
 * 활성 여부와 무관하게 저장된 조건을 돌려준다 — 다시 켤 때 화면이 조건을 잃지 않는다 (명세 1.2).
 */
export const fetchNotificationSubscriptions = () =>
  request<NotificationSubscriptions>({ url: '/me/notification-subscriptions' });

/**
 * NOTI-01 · PUT /api/me/notification-subscriptions — **조회 응답과 동일한 구조로 전체를 전달한다.**
 * 네 항목과 각 enabled는 필수다. 부분 전송이 아니라 전체 전송이라 인자도 조회 응답과 같은 타입이다.
 * 규칙 위반은 400 INVALID_REQUEST이고 error.field에 위치가 담긴다(`newProperty.conditions.districts`).
 */
export const updateNotificationSubscriptions = (subscriptions: NotificationSubscriptions) =>
  request<NotificationSubscriptions>({
    method: 'PUT',
    url: '/me/notification-subscriptions',
    data: subscriptions,
  });
