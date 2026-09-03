# 전월세 부동산 금융 플랫폼 — API 명세서 — 위험도 분석

> 등급 판정, 보증보험, 등기 이력, 재분석
> RISK-01 ~ RISK-08
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 위험도 분석

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| RISK-01<br>RISK-05 | GET | /api/properties/{propertyId}/risk | 위험 등급과 판정 근거 조회. 보증보험 3사 판정 결과를 포함한다 | 선택 |
| RISK-07 | GET | /api/properties/{propertyId}/registry | 등기 갑구·을구 이력 조회 | 선택 |
| RISK-08 | POST | /api/properties/{propertyId}/risk/reanalyze | 위험도 재분석 요청 | 필수 |
| RISK-06 | POST | /api/tools/guarantee-deadline | 보증 신청기한 계산 (저장하지 않음) | 공개 |

깡통전세 판정(RISK-02), 권리 침해 검출(RISK-03), 명의·문서 정합 확인(RISK-04), 보증보험 가입 판정(RISK-05)은 별도 엔드포인트를 두지 않는다. 네 기능의 산출값은 모두 위험도 조회 응답에 판정 근거로 포함된다.
등기 이력은 판정 근거가 아니라 원본 자료이며 매물마다 건수 편차가 크므로 별도 엔드포인트로 분리한다. 위험도 응답에는 판정에 사용된 결론(선순위채권 합계, 권리 침해 항목, 경고 항목)만 담고, 건별 상세는 필요한 시점에 조회한다.

### 1.1 위험도 조회 응답

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| riskGrade | 열거 | SAFE, CAUTION, DANGER |
| gradeReason | 열거 | 등급 결정 사유 (깡통전세 해당, 3사 가입 불가, 전세가율 초과 등) |
| debtRatio | 실수 | 전세가율 (%) |
| marketPrice | 정수 | 적용 시세 (원) |
| priceType / priceDate | 열거 / 일자 | 시세 산출 출처와 기준일 |
| seniorDebtTotal | 정수 | 선순위채권 합계 (원) |
| isNegativeEquity | 논리 | 깡통전세 해당 여부. 선순위채권과 보증금의 합이 시세 기준 안전선을 초과한 상태 |
| insuranceEligible | 논리 | 3사 중 하나 이상 가입 가능 여부 |
| providers[].provider | 열거 | HUG, HF, SGI |
| providers[].eligible | 논리 | 기관별 가입 가능 여부 |
| providers[].failedConditions[] | 배열 | 위배된 집 단위 조건 목록 |
| providers[].guaranteeLimit | 정수 | 보증한도 (주택가격 × 담보인정비율 − 선순위채권) |
| providers[].estimatedPremium | 정수 | 예상 보증료 (원) |
| providers[].productName | 문자열 | 가입 가능한 보증 상품명 |
| personalConditions[] | 배열 | 시스템이 판정하지 않는 개인 자격 확인 사항 |
| rightViolations[] | 배열 | 판정에 반영된 권리 침해 항목 (압류·가압류·경매개시결정·신탁) |
| warnings[] | 배열 | 판정에 반영되지 않는 경고 (가등기·임차권등기명령 등) |
| consistency | 객체 | 명의 일치·주소 일치·위반건축물·면적 대조 결과 |
| analyzedAt | 일시 | 분석 기준 시각 |

GET /api/properties/1024/risk — 응답

```json
{
  "success": true,
  "data": {
    "riskGrade": "DANGER",
    "gradeReason": "NEGATIVE_EQUITY",
    "debtRatio": 116.7,
    "marketPrice": 300000000,
    "priceType": "ACTUAL_TRANSACTION",
    "priceDate": "2026-06-30",
    "seniorDebtTotal": 250000000,
    "isNegativeEquity": true,
    "insuranceEligible": false,
    "providers": [
      {
        "provider": "HUG",
        "eligible": false,
        "failedConditions": ["DEBT_RATIO_EXCEEDED"],
        "guaranteeLimit": 20000000,
        "estimatedPremium": null,
        "productName": null
      },
      {
        "provider": "HF",
        "eligible": false,
        "failedConditions": ["DEBT_RATIO_EXCEEDED", "LOAN_LINK_REQUIRED"],
        "guaranteeLimit": 20000000,
        "estimatedPremium": null,
        "productName": null
      },
      {
        "provider": "SGI",
        "eligible": false,
        "failedConditions": ["DEBT_RATIO_EXCEEDED"],
        "guaranteeLimit": 20000000,
        "estimatedPremium": null,
        "productName": null
      }
    ],
    "personalConditions": ["ANNUAL_INCOME", "APPLICATION_DEADLINE"],
    "rightViolations": [],
    "warnings": ["PROVISIONAL_REGISTRATION"],
    "consistency": {
      "ownerNameMatched": true,
      "addressMatched": true,
      "violationBuilding": false,
      "areaMatched": true
    },
    "analyzedAt": "2026-07-29T03:00:00+09:00"
  }
}
```

### 1.2 재분석 요청

- 동일 매물에 대한 사용자 요청 재분석은 최소 간격을 두며, 간격 내 재요청은 429로 응답하고 다음 요청 가능 시각을 반환한다.
- 일 1회 배치가 관심 매물의 등기를 재조회하므로, 사용자 요청은 즉시성이 필요한 경우에 한한다.
- 처리 중 동일 매물에 대한 중복 요청은 진행 중인 분석 결과를 기다린다.

### 1.3 등기 이력 응답

GET /api/properties/1024/registry

```json
{
  "success": true,
  "data": {
    "propertyId": 1024,
    "ownerships": [
      {
        "rankNo": 2,
        "rightType": "OWNERSHIP_TRANSFER",
        "holderName": "김임대",
        "receivedDate": "2019-03-11",
        "cause": "매매",
        "isActive": true
      }
    ],
    "mortgages": [
      {
        "rankNo": 1,
        "creditor": "○○은행",
        "maxClaimAmount": 250000000,
        "receivedDate": "2019-03-11",
        "isActive": true
      }
    ],
    "collectedAt": "2026-07-29T03:00:00+09:00",
    "dataSource": "MOCK"
  }
}
```

### 1.4 재분석 응답

POST /api/properties/1024/risk/reanalyze — 성공

```json
{
  "success": true,
  "data": {
    "propertyId": 1024,
    "previousGrade": "CAUTION",
    "riskGrade": "DANGER",
    "gradeChanged": true,
    "analyzedAt": "2026-07-29T10:12:00+09:00"
  }
}
```

간격 제한 — 429

```json
{
  "success": false,
  "error": {
    "code": "RISK_REANALYZE_TOO_SOON",
    "message": "재분석은 잠시 후 다시 요청할 수 있습니다.",
    "retryAfter": "2026-07-29T11:00:00+09:00"
  }
}
```

### 1.5 보증 신청기한 계산

POST /api/tools/guarantee-deadline — 요청

```json
{
  "contractStartDate": "2026-03-01",
  "contractMonths": 24
}
```

응답

```json
{
  "success": true,
  "data": {
    "deadlineDate": "2027-03-01",
    "daysRemaining": 215,
    "applicable": true
  }
}
```