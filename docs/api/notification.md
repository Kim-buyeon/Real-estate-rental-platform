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

- 요청 파라미터는 `cursor` · `size`이고 규칙은 공통 규약 1.4를 따른다. 정렬은 최신 알림 먼저(`notificationId` 내림차순)다.
- 목록은 로그인한 사용자의 알림만 담는다. 읽은 알림도 함께 돌려준다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| type | string | `RISK_CHANGE`(관심 매물 위험 등급 변경) · `REGISTRY_CHANGE`(관심 매물 등기 갑구 · 을구 변동) |
| title | string | 유형별 고정 문구. `RISK_CHANGE` 「관심 매물의 위험 등급이 변경되었습니다」, `REGISTRY_CHANGE` 「관심 매물의 등기에 변동이 생겼습니다」 |
| propertyId | number | 알림이 가리키는 매물. 관심 매물을 해제한 뒤에도 남는다 |
| beforeValue · afterValue | string | 변동 전 · 후 값. `RISK_CHANGE`는 위험 등급 상수명(`CAUTION` → `DANGER`), `REGISTRY_CHANGE`는 갑구 · 을구 유효 건수 요약(「갑구 2 · 을구 1」) |
| isRead | boolean | 읽음 여부 |
| createdAt | string | 알림 생성 시각 |
| unreadCount | number | 페이지와 무관한 이 사용자의 읽지 않은 알림 전체 수. 목록의 전체 건수가 아니다 — 공통 규약 1.4 |

**읽음 처리**

- `PATCH /api/notifications/{notificationId}/read` — 존재하지 않거나 다른 사용자의 알림이면 404 `NOTIFICATION_NOT_FOUND`다. 다른 사용자 알림의 존재를 드러내지 않도록 두 경우를 구분하지 않는다. 이미 읽은 알림도 200이다.
- `PATCH /api/notifications/read-all` — 로그인한 사용자의 읽지 않은 알림을 모두 읽음으로 바꾼다. 바꿀 알림이 없어도 200이다.

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