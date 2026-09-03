# 전월세 부동산 금융 플랫폼 — API 명세서 — 회원 · 인증

> 가입, 로그인, 프로필, 탈퇴
> USER-01 ~ USER-05
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 회원 · 인증

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| USER-01 | POST | /api/auth/signup | 이메일 회원 가입 | 공개 |
| USER-01 | POST | /api/auth/oauth/{provider} | 소셜 인증 코드로 가입·로그인 (kakao, naver) | 공개 |
| USER-02 | POST | /api/auth/login | 이메일 로그인, 토큰 발급 | 공개 |
| USER-02 | POST | /api/auth/reissue | 리프레시 토큰으로 액세스 토큰 재발급 | 공개 |
| USER-02 | POST | /api/auth/logout | 리프레시 토큰 폐기 | 필수 |
| USER-03 | GET | /api/me/profile | 계정 정보 및 자격 정보 조회 | 필수 |
| USER-03 | PUT | /api/me/profile | 계정 정보 및 자격 정보 수정 | 필수 |
| USER-04 | GET | /api/me/search-history | 검색 이력 조회 | 필수 |
| USER-04 | DELETE | /api/me/search-history | 검색 이력 삭제 | 필수 |
| USER-05 | DELETE | /api/me | 회원 탈퇴 | 필수 |

### 1.1 프로필 조회 · 수정

조회와 수정이 동일한 중첩 구조를 사용한다. 계정 정보와 자격 정보를 분리해 담으며, 수정 요청에는 조회 결과에서 수정 가능한 필드만 그대로 담아 전체를 전달한다.
account — 계정 정보

| 필드 | 타입 | 수정 | 설명 |
| --- | --- | --- | --- |
| name | 문자열 | 가능 | 이름 |
| phone | 문자열 | 가능 | 전화번호 |
| email | 문자열 | 불가 | 계정 이메일. 변경은 별도 절차로 처리한다 |
| role | 열거 | 불가 | 권한 USER, ADMIN |
| createdAt | 일시 | 불가 | 가입일시 |

profile — 자격 정보

| 필드 | 타입 | 수정 | 설명 |
| --- | --- | --- | --- |
| annualIncome | 정수 | 가능 | 연 소득 (원) |
| creditScore | 정수 | 가능 | 신용점수 |
| existingLoan | 정수 | 가능 | 기존 대출 잔액 (원) |
| existingLoanAnnualPayment | 정수 | 가능 | 기존 대출 연간 상환액 (원) |
| hasHouse | 논리 | 가능 | 주택 보유 여부 |
| ownFund | 정수 | 가능 | 계약에 투입 가능한 자기자금 (원) |

- 수정 요청에는 수정 가능한 필드를 모두 담는다. 화면에서 조회 결과를 그대로 채운 뒤 변경된 값만 바꿔 제출하는 방식을 전제한다.
- 수정 불가 필드가 요청에 포함되면 무시한다.
- 자격 정보 중 미입력 항목은 값을 0 또는 null로 전달한다. 미입력 상태가 유지되면 대출 한도 계산과 계약 가능 매물 제시가 제한되며, 조회 응답의 missingFields로 확인할 수 있다.
GET /api/me/profile — 응답

```json
{
  "success": true,
  "data": {
    "account": {
      "name": "홍길동",
      "phone": "010-1234-5678",
      "email": "user@example.com",
      "role": "USER",
      "createdAt": "2026-07-01T09:12:00+09:00"
    },
    "profile": {
      "annualIncome": 42000000,
      "creditScore": 820,
      "existingLoan": 0,
      "existingLoanAnnualPayment": 0,
      "hasHouse": false,
      "ownFund": 50000000
    },
    "missingFields": []
  }
}
```

PUT /api/me/profile — 요청

```json
{
  "account": {
    "name": "홍길동",
    "phone": "010-1234-5678"
  },
  "profile": {
    "annualIncome": 45000000,
    "creditScore": 820,
    "existingLoan": 0,
    "existingLoanAnnualPayment": 0,
    "hasHouse": false,
    "ownFund": 50000000
  }
}
```

### 1.2 인증 요청 · 응답

POST /api/auth/signup — 요청

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd!",
  "name": "홍길동",
  "phone": "010-1234-5678"
}
```

POST /api/auth/login — 요청

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd!"
}
```

POST /api/auth/oauth/{provider} — 요청

```json
{
  "authorizationCode": "0PdJ2kL9...",
  "redirectUri": "https://example.com/oauth/callback"
}
```

로그인 · 소셜 인증 · 재발급 공통 응답

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "isNewUser": false
  }
}
```

POST /api/auth/reissue — 요청

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

- 로그아웃, 검색 이력 삭제, 회원 탈퇴는 본문이 없으며 성공 시 data를 null로 반환한다.