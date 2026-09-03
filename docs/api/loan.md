# 전월세 부동산 금융 플랫폼 — API 명세서 — 대출

> 한도 계산, 정책상품 자격, 상품 추천, 대출 계획
> LOAN-01 ~ LOAN-06
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 대출

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| LOAN-01 | GET | /api/loans/limit | 규제별 대출 한도 계산 | 필수 |
| LOAN-02 | GET | /api/loans/policy-eligibility | 정책상품 자격 항목별 검토 | 필수 |
| LOAN-03 | GET | /api/loans/recommendations | 매물 기준 대출 상품 추천 | 필수 |
| LOAN-04 | POST | /api/loans/simulate | 상환방식별 상환액 시뮬레이션 | 공개 |
| LOAN-05 | POST | /api/loan-plans | 대출 계획 저장 | 필수 |
| LOAN-05 | GET | /api/loan-plans | 대출 계획 목록 조회 | 필수 |
| LOAN-06 | GET | /api/rates/history | 기준금리·상품 금리 변동 이력 | 공개 |

### 1.1 한도 계산 응답

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| ltvLimit | 정수 | 담보인정비율 기준 한도 (원) |
| dsrLimit | 정수 | 총부채원리금상환비율 기준 한도 (원) |
| stressDsrLimit | 정수 | 가산금리 적용 한도 (원) |
| finalLimit | 정수 | 세 한도 중 최솟값 |
| appliedRegulation | 문자열 | 최종 한도를 결정한 규제 항목 |
| dtiReference | 실수 | 참고용 총부채상환비율 (한도 판정에 미사용) |
| missingFields[] | 배열 | 계산에 필요하나 미입력된 자격 정보 항목 |

자격 정보가 부족하면 422로 응답하고 missingFields를 반환한다. 다만 소득 구간을 파라미터로 전달하면 해당 구간 기준의 추정 한도를 반환한다.
GET /api/loans/limit?propertyId=1024 — 응답

```json
{
  "success": true,
  "data": {
    "ltvLimit": 240000000,
    "dsrLimit": 180000000,
    "stressDsrLimit": 162000000,
    "finalLimit": 162000000,
    "appliedRegulation": "STRESS_DSR",
    "dtiReference": 38.5,
    "missingFields": []
  }
}
```

### 1.2 상품 추천 응답

추천은 두 단계 관문을 거친다. 대상 매물의 보증보험 가입이 불가하면 추천 목록을 비우고 사유를 반환한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| eligibleProperty | 논리 | 1단계 관문 통과 여부 |
| blockedReason | 문자열 | 미통과 시 사유 |
| products[].productName | 문자열 | 상품명 |
| products[].interestRate | 실수 | 적용 금리 (%) |
| products[].limit | 정수 | 산정된 한도 (원) |
| products[].repaymentType | 열거 | 상환 방식 |
| products[].isPolicyProduct | 논리 | 정책상품 여부 |
| products[].unmetConditions[] | 배열 | 미충족 자격 요건 |

### 1.3 정책상품 자격 검토 응답

```json
{
  "success": true,
  "data": {
    "productName": "버팀목 전세자금대출",
    "eligible": false,
    "checked": [
      { "condition": "INCOME_LIMIT", "satisfied": true, "detail": "부부합산 소득 기준 이하" },
      { "condition": "NO_HOUSE", "satisfied": true, "detail": "무주택" },
      { "condition": "NO_EXISTING_LOAN", "satisfied": false, "detail": "기존 주택 관련 대출 보유" }
    ],
    "manualCheckRequired": ["HOUSEHOLD_HEAD", "DEPOSIT_PARTIAL_PAYMENT"]
  }
}
```

### 1.4 상품 추천 예시

GET /api/loans/recommendations?propertyId=1024

```json
{
  "success": true,
  "data": {
    "eligibleProperty": true,
    "blockedReason": null,
    "products": [
      {
        "productId": 31,
        "productName": "버팀목 전세자금대출",
        "interestRate": 2.4,
        "limit": 120000000,
        "repaymentType": "INTEREST_ONLY",
        "loanTermMonths": 24,
        "isPolicyProduct": true,
        "unmetConditions": []
      },
      {
        "productId": 12,
        "productName": "○○은행 전세자금대출",
        "interestRate": 3.9,
        "limit": 162000000,
        "repaymentType": "INTEREST_ONLY",
        "loanTermMonths": 24,
        "isPolicyProduct": false,
        "unmetConditions": []
      }
    ]
  }
}
```

추천 불가 — 1단계 관문 미통과

```json
{
  "success": true,
  "data": {
    "eligibleProperty": false,
    "blockedReason": "INSURANCE_NOT_ELIGIBLE",
    "products": []
  }
}
```

### 1.5 상환 시뮬레이션

POST /api/loans/simulate — 요청

```json
{
  "principal": 150000000,
  "interestRate": 3.9,
  "loanTermMonths": 24,
  "repaymentTypes": ["EQUAL_PRINCIPAL_INTEREST", "EQUAL_PRINCIPAL", "INTEREST_ONLY"]
}
```

응답

```json
{
  "success": true,
  "data": {
    "results": [
      {
        "repaymentType": "EQUAL_PRINCIPAL_INTEREST",
        "monthlyPayment": 6506000,
        "firstMonthPayment": 6506000,
        "totalInterest": 6144000
      },
      {
        "repaymentType": "INTEREST_ONLY",
        "monthlyPayment": 487500,
        "firstMonthPayment": 487500,
        "totalInterest": 11700000
      }
    ]
  }
}
```

### 1.6 대출 계획 저장

POST /api/loan-plans — 요청

```json
{
  "propertyId": 1024,
  "productId": 12,
  "loanAmount": 150000000,
  "loanTermMonths": 24,
  "repaymentType": "INTEREST_ONLY"
}
```

응답

```json
{
  "success": true,
  "data": {
    "planId": 77,
    "propertyId": 1024,
    "productName": "○○은행 전세자금대출",
    "loanAmount": 150000000,
    "monthlyPayment": 487500,
    "ltvResult": 44.1,
    "dsrResult": 21.3,
    "stressDsrResult": 26.8,
    "createdAt": "2026-07-29T10:30:00+09:00"
  }
}
```

### 1.7 금리 변동 이력 응답

GET /api/rates/history?rateType=BASE_RATE&months=12

```json
{
  "success": true,
  "data": {
    "rateType": "BASE_RATE",
    "series": [
      { "changeDate": "2026-05-29", "rateValue": 2.75, "source": "BOK" },
      { "changeDate": "2026-07-10", "rateValue": 2.5, "source": "BOK" }
    ],
    "collectedFrom": "2026-07-01"
  }
}
```

- 상품 금리는 수집 시점 이후의 데이터만 존재하므로 collectedFrom을 함께 반환한다.