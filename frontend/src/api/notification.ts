// 알림 API 명세 — 명세 표의 행 하나 = 함수 하나. 이 슬라이스가 만드는 것은 NOTI-05 세 행
// (목록 · 개별 읽음 · 전체 읽음)이다. NOTI-01 구독 설정은 다음 슬라이스, NOTI-04 푸시 토큰은
// 차기 · 조건부 범위라 함수를 만들지 않는다 (frontend/CLAUDE.md API 함수).
// NOTI-03 실시간 수신 행은 request<T>()가 아니라 EventSource이며 app/NotificationStream.tsx가 갖는다.
import type { NotificationType } from '../domain/notification';
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
