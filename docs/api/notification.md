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

| 항목 | 규약 |
| --- | --- |
| 응답 형식 | `text/event-stream`. 공통 응답 봉투로 감싸지 않는다. 인증 실패는 스트림을 열기 전에 공통 규약의 401 봉투로 응답한다 |
| 인증 | `Authorization: Bearer` 헤더 또는 쿼리 파라미터 `accessToken`. 표준 `EventSource`는 헤더를 붙일 수 없어 이 경로에서만 쿼리 파라미터를 받는다. 둘 다 있으면 헤더를 쓴다 |
| 연결 수명 | 액세스 토큰 유효 시간과 같다(30분). 서버가 연결을 닫으면 클라이언트는 유효한 토큰으로 재연결하고 목록을 재조회한다 |
| 연결 직후 | 주석 한 줄(`:connected`)을 보낸다. 이벤트로 전달되지 않는다 |
| 하트비트 | 이벤트가 없어도 일정 간격으로 주석(`:heartbeat`)을 보낸다. 간격은 미확정이며 부하 시험에서 확정한다(잠정 25초) |

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

- 수정 요청은 조회 응답과 동일한 구조로 전체를 전달한다. 네 항목과 각 `enabled`는 필수다. 수정 응답은 조회 응답과 같다.
- 조건은 신규 매물만 갖는다. 금리 변동 · 관심 매물 모니터링 · 상담 일정은 수신 여부만 갖는다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| newProperty.conditions | object | `enabled`가 true면 필수 | |
| newProperty.conditions.districts | string[] | `enabled`가 true면 1개 이상 | 서울 자치구명(「강서구」). 중복 없음, 최대 25개. `enabled`가 false여도 온 값은 같은 규칙으로 검증한다 |
| newProperty.conditions.contractType | string | 선택 | `DEPOSIT_ONLY` · `MONTHLY_RENT` · `SEMI_DEPOSIT`. 생략하면 계약 유형 전체 |
| newProperty.conditions.depositMax | number | 선택 | 원. 생략하면 상한 없음 |

- 위 규칙 위반과 열거에 없는 값은 400 `INVALID_REQUEST`이고 `field`에 위치를 담는다(예: `newProperty.conditions.districts`).
- `enabled`가 false인데 조건이 오면 조건을 보존한다. 조회는 활성 여부와 무관하게 저장된 조건을 돌려준다 — 다시 켤 때 화면이 조건을 잃지 않는다.
- 설정한 적 없는 항목의 조회 값은 `wishlistMonitoring`만 true, 나머지는 false다. 조건이 없으면 `districts`는 빈 배열, `contractType` · `depositMax`는 null이다.
- `wishlistMonitoring.enabled`는 등록된 관심 매물 전체의 모니터링 여부에 일괄 반영되고, 이후 등록하는 관심 매물도 이 값을 따른다.

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

```
event:RISK_CHANGE
data:{"notificationId":9012,"type":"RISK_CHANGE","propertyId":1024,"createdAt":"2026-07-29T03:05:00+09:00"}
```

- 이벤트 이름은 알림 유형과 동일하게 지정한다. 본문에는 식별자와 유형만 담고, 상세 내용은 목록 조회로 가져온다.
- 한 사용자가 연결을 여럿 열었으면(탭 여러 개) 모든 연결로 보낸다. 연결이 없는 동안의 알림은 다시 보내지 않는다 — 목록 조회로 확인한다.
- 푸시 토큰 등록·해제와 읽음 처리는 본문 응답의 data를 null로 반환한다.