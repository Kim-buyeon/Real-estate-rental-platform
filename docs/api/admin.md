# 전월세 부동산 금융 플랫폼 — API 명세서 — 관리자

> 판정 기준 데이터 관리
> ADMIN-01
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 관리자

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| ADMIN-01 | GET | /api/admin/criteria/guarantee | 보증보험 기관별 기준 조회 | 관리자 |
| ADMIN-01 | PUT | /api/admin/criteria/guarantee/{provider} | 기관별 기준 수정 | 관리자 |
| ADMIN-01 | GET | /api/admin/criteria/premium-rates | 보증료율 조회 | 관리자 |
| ADMIN-01 | PUT | /api/admin/criteria/premium-rates | 보증료율 수정 | 관리자 |
| ADMIN-01 | GET | /api/admin/criteria/loan-regulations | 대출 규제 수치 조회 | 관리자 |
| ADMIN-01 | PUT | /api/admin/criteria/loan-regulations | 대출 규제 수치 수정 | 관리자 |
| ADMIN-01 | GET | /api/admin/criteria/risk-thresholds | 위험 등급 기준값 조회 | 관리자 |
| ADMIN-01 | PUT | /api/admin/criteria/risk-thresholds | 위험 등급 기준값 수정 | 관리자 |
| ADMIN-01 | GET | /api/admin/criteria/history | 기준 데이터 변경 이력 조회 | 관리자 |

수정 요청에는 변경 사유를 함께 전달하며, 변경 전후 값과 함께 이력으로 기록한다. 변경은 이후 판정부터 적용되고 기존 분석 이력은 소급 재계산하지 않는다.

### 1.1 기준 조회 · 수정 예시

GET /api/admin/criteria/guarantee — 응답

```json
{
  "success": true,
  "data": {
    "providers": [
      {
        "provider": "HUG",
        "collateralRatio": 90.0,
        "maxDeposit": 700000000,
        "requiresLoanLink": false,
        "updatedAt": "2026-07-01T00:00:00+09:00"
      }
    ]
  }
}
```

PUT /api/admin/criteria/guarantee/HUG — 요청

```json
{
  "collateralRatio": 90.0,
  "maxDeposit": 500000000,
  "requiresLoanLink": false,
  "changeReason": "보증금 한도 예시 — 비수도권 5억 적용"
}
```

- 조회 응답에는 `seniorDebtRatioLimit`(선순위채권 한도, %)가 함께 나간다. 값이 확인되지 않은 기관은 `null`이며 검사하지 않는다.
- 수정 요청은 `collateralRatio`(0 ~ 100) · `maxDeposit`(0 이상) · `requiresLoanLink` · `changeReason`(필수, 200자 이하)이 필수이고 `seniorDebtRatioLimit`(0 ~ 100)은 선택이다. `seniorDebtRatioLimit`이 없으면 기존 값을 유지한다 — 이력의 변경 후 값이 비어 있을 수 없어 `null`로 되돌리는 수정은 받지 않는다.
- `requiresLoanLink`는 HF만 저장한다. HUG · SGI는 늘 `false`로 조회되고 요청 값은 무시한다.
- `collateralRatio`는 위험 등급 기준 `negativeEquityRatio`보다 커야 한다(아래 세 선 단조). 어기면 400 `INVALID_REQUEST`.
- 비율은 소수 둘째 자리까지 받는다.

GET /api/admin/criteria/premium-rates — 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "premiumRateId": 1,
        "provider": "HUG",
        "houseType": "APARTMENT",
        "depositMin": 0,
        "depositMax": 200000000,
        "debtRatioMin": 0.00,
        "debtRatioMax": 80.00,
        "premiumRate": 0.097,
        "updatedAt": "2026-09-13T00:00:00+09:00"
      }
    ]
  }
}
```

PUT /api/admin/criteria/premium-rates — 요청. 구간(보증금 · 부채비율 · 주택 유형)은 바꾸지 않고 요율 값만 수정한다. `premiumRate`는 0 이상 100 미만, 소수 셋째 자리까지. 없는 `premiumRateId`나 같은 식별자가 두 번 오면 400 `INVALID_REQUEST`.

```json
{
  "rates": [
    { "premiumRateId": 1, "premiumRate": 0.115 }
  ],
  "changeReason": "요율 개정 반영"
}
```

GET /api/admin/criteria/risk-thresholds — 응답

```json
{
  "success": true,
  "data": {
    "negativeEquityRatio": 80.0,
    "cautionLeaseRatio": 70.0,
    "updatedAt": "2026-09-13T00:00:00+09:00"
  }
}
```

PUT 요청은 두 값과 `changeReason`을 받는다. `cautionLeaseRatio`는 `negativeEquityRatio`보다 작아야 한다 — 같거나 크면 CAUTION이 나올 수 없다(비즈니스 로직 정의서 3장). 어기면 400 `INVALID_REQUEST`. `negativeEquityRatio`는 보증기관 `collateralRatio`의 최솟값보다 작아야 한다 — 세 선 `cautionLeaseRatio < negativeEquityRatio < min(collateralRatio)`. 어기면 400 `INVALID_REQUEST`.

수정(PUT) 응답은 같은 경로 조회(GET)의 응답과 같다 — 기관별 기준 수정은 `GET /api/admin/criteria/guarantee`와 같은 `providers[]`. 값이 바뀐 필드만 이력으로 남고, 바뀐 필드가 없으면 이력을 남기지 않는다.

GET /api/admin/criteria/loan-regulations — 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "regulationId": 1,
        "houseType": "ALL",
        "regionType": "SEOUL_REGULATED",
        "depositRatioLimit": 80.00,
        "guaranteeCapNoHouse": 400000000,
        "guaranteeCapOneHouse": 180000000,
        "dsrLimit": 40.00,
        "stressDsrRate": 3.00,
        "dtiLimit": 40.00,
        "effectiveDate": "2025-10-29"
      }
    ]
  }
}
```

- 대출 규제 기준 테이블에 수정일시 컬럼이 없어 `updatedAt`을 내보내지 않는다. 변경 시각은 변경 이력(1.2)으로 본다.
- 정렬은 `effectiveDate` 내림차순, 같으면 `regulationId` 내림차순.

PUT /api/admin/criteria/loan-regulations — 요청. 적용 대상(`houseType` · `regionType` · `effectiveDate`)은 바꾸지 않고 수치 여섯만 수정한다. 여섯 값과 `changeReason`(필수, 200자 이하)이 모두 필수다.

```json
{
  "regulations": [
    {
      "regulationId": 1,
      "depositRatioLimit": 80.00,
      "guaranteeCapNoHouse": 400000000,
      "guaranteeCapOneHouse": 180000000,
      "dsrLimit": 40.00,
      "stressDsrRate": 3.00,
      "dtiLimit": 40.00
    }
  ],
  "changeReason": "대출 규제 개정 반영"
}
```

- `depositRatioLimit` · `dsrLimit` · `stressDsrRate` · `dtiLimit`은 0 이상 100 미만, 소수 둘째 자리까지. `guaranteeCapNoHouse` · `guaranteeCapOneHouse`는 0 이상 정수(원).
- 없는 `regulationId`나 같은 식별자가 두 번 오면 400 `INVALID_REQUEST`.

### 1.2 변경 이력 응답

- 파라미터 — `cursor`(선택), `size`(기본 20, 최대 100)
- `field` — 변경된 API 필드명. `beforeValue` · `afterValue`는 문자열이다(비율은 저장 자릿수 그대로, 금액은 정수). 처음 값이 없던 필드는 `beforeValue`가 `null`
- `targetKey` — 사람이 읽는 대상 식별. 기관 기준은 `HUG`, 보증료율은 `HUG/APARTMENT/0-200000000/0.00-80.00`(보증금 상한이 없으면 비움), 대출 규제는 `SEOUL_REGULATED/ALL`(지역 유형/주택 유형), 위험 등급 기준은 `RISK_CRITERIA`

- `target` — `GUARANTEE_CRITERIA` · `HUG_CRITERIA` · `HF_CRITERIA` · `SGI_CRITERIA` · `GUARANTEE_PREMIUM_RATE` · `LOAN_REGULATION` · `RISK_CRITERIA`
- `changedBy` — 변경한 관리자의 이메일. 탈퇴해 개인 정보가 파기된 관리자는 `null`
- 정렬은 `changedAt` 내림차순, 같으면 `historyId` 내림차순

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "historyId": 14,
        "target": "GUARANTEE_CRITERIA",
        "targetKey": "HUG",
        "field": "maxDeposit",
        "beforeValue": "700000000",
        "afterValue": "500000000",
        "changeReason": "보증금 한도 예시 — 비수도권 5억 적용",
        "changedBy": "admin@example.com",
        "changedAt": "2026-07-29T11:00:00+09:00"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```