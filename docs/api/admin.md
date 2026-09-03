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
  "collateralRatio": 80.0,
  "maxDeposit": 700000000,
  "requiresLoanLink": false,
  "changeReason": "2026년 하반기 보증비율 조정 반영"
}
```

### 1.2 변경 이력 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "historyId": 14,
        "target": "GUARANTEE_CRITERIA",
        "targetKey": "HUG",
        "field": "collateralRatio",
        "beforeValue": "90.0",
        "afterValue": "80.0",
        "changeReason": "2026년 하반기 보증비율 조정 반영",
        "changedBy": "admin@example.com",
        "changedAt": "2026-07-29T11:00:00+09:00"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```