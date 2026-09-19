# 전월세 부동산 금융 플랫폼 — API 명세서 — 회원 · 인증

> 가입, 로그인, 프로필, 탈퇴, 비밀번호 재설정
> USER-01 ~ USER-06
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
| USER-06 | POST | /api/auth/password-reset | 비밀번호 재설정 메일 요청 | 공개 |
| USER-06 | POST | /api/auth/password-reset/confirm | 재설정 토큰으로 새 비밀번호 설정 | 공개 |

### 1.1 프로필 조회 · 수정

조회와 수정이 동일한 중첩 구조를 사용한다. 계정 정보와 자격 정보를 분리해 담으며, 수정 요청에는 조회 결과에서 수정 가능한 필드만 그대로 담아 전체를 전달한다.
account — 계정 정보

| 필드 | 타입 | 수정 | 설명 |
| --- | --- | --- | --- |
| name | 문자열 | 가능 | 이름 |
| phone | 문자열 | 가능 | 전화번호. 가입할 때 입력하지 않았으면 null — 가입 요청에서 선택 항목이다(1.2) |
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

- `phone`은 선택 항목이다. 빼거나 null로 보내면 null로 저장되고, 프로필 조회(1.1)의 `account.phone`도 null로 온다.

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

- 회원 가입, 로그아웃, 검색 이력 삭제, 회원 탈퇴는 본문이 없으며 성공 시 data를 null로 반환한다.

### 1.3 비밀번호 재설정

근거: [OWASP Forgot Password Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html) — 계정 유무와 무관한 같은 응답, 고엔트로피 일회용 토큰, 짧은 수명, 해시 저장, 재설정 후 세션 무효화.

POST /api/auth/password-reset — 요청

```json
{
  "email": "user@example.com"
}
```

| 규약 | 내용 |
| --- | --- |
| 응답 | **항상 200, data null.** 가입되지 않은 이메일 · 소셜 전용 계정 · 발송 간격 안의 재요청도 같다 — 응답으로 가입 여부를 알 수 없게 한다 |
| 응답 시간 | 메일은 응답 뒤 비동기로 보낸다. 계정 유무로 응답 시간이 갈리지 않게 한다 |
| 메일 | 비밀번호로 가입한 계정에만 보낸다. 본문의 링크는 화면 경로 `/password-reset/confirm?token=<토큰>`이다 |
| 발송 간격 | 같은 이메일로는 60초에 한 번만 보낸다. 간격 안의 요청도 200이다 |
| 토큰 | 32바이트 난수(URL 안전 Base64). 서버는 해시만 보관한다. **수명 30분 · 한 번 쓰면 사라진다.** 새로 요청하면 이전 토큰은 무효가 된다 |
| 형식 오류 | 이메일 형식이 아니면 400 `INVALID_REQUEST` — 가입 여부와 무관한 입력 검증이다 |

POST /api/auth/password-reset/confirm — 요청

```json
{
  "token": "Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl",
  "newPassword": "N3wP@ssw0rd!"
}
```

| 규약 | 내용 |
| --- | --- |
| 성공 | 200, data null. 비밀번호를 바꾸고 **그 회원의 리프레시 토큰을 폐기**한다 — 다른 기기의 로그인이 재발급에서 끊긴다. 발급된 액세스 토큰은 만료(최대 30분)까지 남는다 |
| 토큰 무효 | 없는 토큰 · 만료 · 이미 사용 · 이후 요청으로 대체됨 — 모두 400 `AUTH_RESET_TOKEN_INVALID`. 사유를 구분하지 않는다 |
| 비밀번호 규칙 | 가입과 같다. 어기면 400 `INVALID_REQUEST`, 오류 봉투의 `field`에 `newPassword` — 이때 토큰은 소비하지 않는다 |
| 로그인 | 재설정은 로그인을 대신하지 않는다. 성공 뒤 새 비밀번호로 로그인한다 |
