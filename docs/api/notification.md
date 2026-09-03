# 전월세 부동산 금융 플랫폼 — API 명세서 — 알림

> 구독 설정, 실시간 수신, 웹 푸시, 알림 목록
> NOTI-01 ~ NOTI-05
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 알림

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| NOTI-01 | GET | /api/me/notification-subscriptions | 알림 구독 설정 조회 | 필수 |
| NOTI-01 | PUT | /api/me/notification-subscriptions | 알림 유형별 수신 여부·구독 조건 설정 | 필수 |
| NOTI-03 | GET | /api/notifications/stream | 실시간 알림 수신 (SSE) | 필수 |
| NOTI-04 | POST | /api/notifications/push-tokens | 웹 푸시 토큰 등록 | 필수 |
| NOTI-04 | DELETE | /api/notifications/push-tokens/{token} | 푸시 토큰 해제 | 필수 |
| NOTI-05 | GET | /api/notifications | 알림 목록 조회 (커서 페이지네이션) | 필수 |
| NOTI-05 | PATCH | /api/notifications/{notificationId}/read | 개별 알림 읽음 처리 | 필수 |
| NOTI-05 | PATCH | /api/notifications/read-all | 전체 읽음 처리 | 필수 |

알림 생성(NOTI-02)은 배치와 도메인 이벤트가 수행하는 내부 처리이므로 외부 엔드포인트를 제공하지 않는다.

### 1.1 실시간 수신 규약

- 연결은 로그인 이후 애플리케이션 전역에 하나만 유지한다. 이벤트 종류는 알림 유형과 동일하게 구분한다.
- 전송하는 데이터는 알림 식별자와 유형, 관련 자원 식별자로 한정한다. 클라이언트는 수신 후 목록 조회로 본문을 가져온다.
- 실시간 연결이 끊긴 상태에서도 목록 조회로 모든 알림을 확인할 수 있어야 한다.

### 1.2 구독 설정 조회 · 수정

GET · PUT /api/me/notification-subscriptions — 공통 구조

```json
{
  "success": true,
  "data": {
    "newProperty": {
      "enabled": true,
      "conditions": {
        "districts": ["강서구", "구로구"],
        "contractType": "DEPOSIT_ONLY",
        "depositMax": 250000000
      }
    },
    "rateChange": { "enabled": true },
    "wishlistMonitoring": { "enabled": true },
    "consultSchedule": { "enabled": true }
  }
}
```

- 수정 요청은 조회 응답과 동일한 구조로 전체를 전달한다.

### 1.3 알림 목록 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "notificationId": 9012,
        "type": "RISK_CHANGE",
        "title": "관심 매물의 위험 등급이 변경되었습니다",
        "propertyId": 1024,
        "beforeValue": "CAUTION",
        "afterValue": "DANGER",
        "isRead": false,
        "createdAt": "2026-07-29T03:05:00+09:00"
      }
    ],
    "nextCursor": "eyJpZCI6OTAxMn0",
    "hasNext": true,
    "unreadCount": 4
  }
}
```

### 1.4 실시간 수신 이벤트 형식

GET /api/notifications/stream — 서버 전송 이벤트

```json
{
  "notificationId": 9012,
  "type": "RISK_CHANGE",
  "propertyId": 1024,
  "createdAt": "2026-07-29T03:05:00+09:00"
}
```

- 이벤트 이름은 알림 유형과 동일하게 지정한다. 본문에는 식별자와 유형만 담고, 상세 내용은 목록 조회로 가져온다.
- 푸시 토큰 등록·해제와 읽음 처리는 본문 응답의 data를 null로 반환한다.