// 알림 도메인 MSW 핸들러. 응답은 알림 API 명세 1.3의 예시 그대로다 (frontend/CLAUDE.md 폴더 구조).
import { http, HttpResponse } from 'msw';
import type { Notification, NotificationPage, NotificationSubscriptions } from '../../../api/notification';

/** 명세 1.3 예시 그대로 — RISK_CHANGE의 beforeValue · afterValue는 위험 등급 상수명이다 */
export const RISK_CHANGE_NOTIFICATION: Notification = {
  notificationId: 9012,
  type: 'RISK_CHANGE',
  title: '관심 매물의 위험 등급이 변경되었습니다',
  propertyId: 1024,
  beforeValue: 'CAUTION',
  afterValue: 'DANGER',
  isRead: false,
  createdAt: '2026-07-29T03:05:00+09:00',
};

/**
 * REGISTRY_CHANGE — beforeValue · afterValue는 갑구 · 을구 유효 건수와 내용 지문 요약 문자열이다
 * (명세 1.3). 위험 등급 상수명과 겹치지 않는 형태로 둔다 — NotificationList.test.tsx가 유형별
 * 표기 분기(domain/notification.ts notificationValueLabel)를 검증할 때 서버가 낼 수 없는 값을
 * 쓰지 않기 위함이다.
 */
export const REGISTRY_CHANGE_NOTIFICATION: Notification = {
  notificationId: 9011,
  type: 'REGISTRY_CHANGE',
  title: '관심 매물의 등기에 변동이 생겼습니다',
  propertyId: 2048,
  beforeValue: '갑구 1 · 을구 1 · a1b2c3d4',
  afterValue: '갑구 2 · 을구 1 · e5f6a7b8',
  isRead: false,
  createdAt: '2026-07-29T02:00:00+09:00',
};

/** 이미 읽은 알림도 함께 온다 (명세 1.3) — isRead:false 건수와 unreadCount를 가르는 세 번째 항목 */
const READ_NOTIFICATION: Notification = {
  notificationId: 9010,
  type: 'RISK_CHANGE',
  title: '관심 매물의 위험 등급이 변경되었습니다',
  propertyId: 1024,
  beforeValue: 'SAFE',
  afterValue: 'CAUTION',
  isRead: true,
  createdAt: '2026-07-28T09:00:00+09:00',
};

/**
 * 알림 목록 첫 쪽. unreadCount(4)를 이 쪽의 isRead:false 건수(2)와 일부러 다르게 둔다 — 명세 1.3
 * 「페이지와 무관한 이 사용자의 읽지 않은 알림 전체 수. 목록의 전체 건수가 아니다」를 검증하려면
 * 화면이 unreadCount를 그대로 쓰는지, 목록에서 세지 않는지를 가를 수 있어야 한다
 * (NotificationList.test.tsx).
 */
export const NOTIFICATION_PAGE_1: NotificationPage = {
  items: [RISK_CHANGE_NOTIFICATION, REGISTRY_CHANGE_NOTIFICATION, READ_NOTIFICATION],
  nextCursor: 'eyJpZCI6OTAxMH0',
  hasNext: true,
  unreadCount: 4,
};

/**
 * 둘째(마지막) 쪽 — 「더 보기」 검증(공통 규약 1.4). notificationHandlers는 요청의 cursor가
 * NOTIFICATION_PAGE_1.nextCursor와 정확히 같을 때만 이 쪽을 준다.
 */
export const NOTIFICATION_PAGE_2: NotificationPage = {
  items: [
    {
      notificationId: 9001,
      type: 'REGISTRY_CHANGE',
      title: '관심 매물의 등기에 변동이 생겼습니다',
      propertyId: 3072,
      beforeValue: '갑구 1 · 을구 0 · b2c3d4e5',
      afterValue: '갑구 1 · 을구 1 · c3d4e5f6',
      isRead: true,
      createdAt: '2026-07-20T08:00:00+09:00',
    },
  ],
  nextCursor: null,
  hasNext: false,
  unreadCount: 4,
};

/**
 * NOTI-05 핸들러 — 명세 1.3 · 1.4. GET은 cursor로 쪽을 가른다: 없으면 첫 쪽, 정확히
 * NOTIFICATION_PAGE_1.nextCursor로 오면 둘째 쪽 — 공통 규약 1.4를 어긴 커서면 첫 쪽으로 되돌아간다
 * (property.ts wishlistHandlers와 같은 방식). 읽음 처리 응답의 data는 null이다(명세 1.4 마지막 줄).
 */
export const notificationHandlers = [
  http.get('/api/notifications', ({ request }) => {
    const cursor = new URL(request.url).searchParams.get('cursor');
    const page = cursor === NOTIFICATION_PAGE_1.nextCursor ? NOTIFICATION_PAGE_2 : NOTIFICATION_PAGE_1;
    return HttpResponse.json({ success: true, data: page });
  }),
  http.patch('/api/notifications/:notificationId/read', () => HttpResponse.json({ success: true, data: null })),
  http.patch('/api/notifications/read-all', () => HttpResponse.json({ success: true, data: null })),
];

/**
 * NOTI-01 구독 설정 — 명세 1.2 예시 그대로. **newProperty.enabled는 false인데 conditions.districts에
 * 값이 남아 있다** — 「enabled가 false여도 저장된 조건을 그대로 돌려준다」(명세 1.2)를 검증하려는
 * SubscriptionForm.test.tsx의 핵심 케이스가 이 픽스처 하나로 선다. contractType · depositMax는 설정한
 * 적 없어 null — 선택 필드가 응답에서 null로 오는 경우(명세 1.2)를 함께 나타낸다.
 */
export const SUBSCRIPTIONS_FIXTURE: NotificationSubscriptions = {
  newProperty: {
    enabled: false,
    conditions: { districts: ['강남구', '서초구'], contractType: null, depositMax: null },
  },
  rateChange: { enabled: true },
  wishlistMonitoring: { enabled: true },
  consultSchedule: { enabled: false },
};

/**
 * NOTI-01 핸들러 — 명세 1.2. PUT은 조회와 같은 구조를 그대로 돌려준다(서버가 값을 다듬지 않는
 * 단순 반영 경로로 둔다) — 요청 본문 검증은 각 테스트가 PUT을 자체 오버라이드해 캡처한다.
 */
export const subscriptionHandlers = [
  http.get('/api/me/notification-subscriptions', () =>
    HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
  ),
  http.put('/api/me/notification-subscriptions', () =>
    HttpResponse.json({ success: true, data: SUBSCRIPTIONS_FIXTURE }),
  ),
];
