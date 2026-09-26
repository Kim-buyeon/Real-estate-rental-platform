# 전월세 부동산 금융 플랫폼 — API 명세서 — 매물

> 지도 탐색, 검색, 관심 매물, 시세 통계
> PROP-01 ~ PROP-08
>
> ※ 요청·응답 형식과 오류 코드는 「API 명세서 — 공통 규약」을 따른다.
> 작성 기준일 : 2026년 7월

## 1. 매물

| 기능 | 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- | --- |
| PROP-08 | GET | /api/properties/district-counts | 필터 조건별 자치구 매물 수 집계 | 선택 |
| PROP-01<br>PROP-02 | GET | /api/properties | 매물 조회. 반경 조건 유무에 따라 목록 또는 지도 마커 형태로 응답 | 선택 |
| PROP-02 | GET | /api/properties/map-clusters | 표시 영역을 격자로 묶은 지도 묶음. 자치구 단계의 호출 (1.12) | 선택 |
| PROP-03 | GET | /api/properties/{propertyId} | 매물 상세 조회 | 선택 |
| PROP-04 | GET | /api/properties/{propertyId}/ledger | 건축물대장 정보 조회 | 선택 |
| PROP-05 | GET | /api/me/wishlist | 관심 매물 목록 조회 | 필수 |
| PROP-05 | POST | /api/me/wishlist | 관심 매물 등록 | 필수 |
| PROP-05 | DELETE | /api/me/wishlist/{propertyId} | 관심 매물 해제 | 필수 |
| PROP-06 | GET | /api/regions/{district}/stats | 자치구 시세 통계 조회 | 공개 |
| PROP-07 | POST | /api/tools/rent-conversion | 전월세 전환 계산 (저장하지 않음) | 공개 |

### 1.1 공통 검색 필터

자치구 집계와 매물 조회는 동일한 필터 파라미터를 사용한다. 지도 단계를 이동하거나 목록과 지도를 오갈 때 조건이 그대로 유지되도록 하기 위함이다.

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| district | 문자열 | 자치구명. 좌표 조건 없이 조회할 때는 지정을 권장한다 |
| contractType | 열거 | DEPOSIT_ONLY(전세) · MONTHLY_RENT(월세) · SEMI_DEPOSIT(반전세) |
| depositMin / depositMax | 정수 | 보증금 범위 (원) |
| monthlyRentMax | 정수 | 월세 상한 (원) |
| propertyType | 열거 | APARTMENT(아파트) · OFFICETEL(오피스텔). 적재 경로가 만드는 값만 둔다 — 전월세 실거래가는 주택 유형마다 서비스가 나뉘고 지금 붙은 것이 둘이다. 연립다세대 · 단독다가구는 그 서비스를 붙이는 변경에서 추가한다 |
| riskGrade | 열거 배열 | SAFE, CAUTION, DANGER 중 다중 선택 |
| areaMin / areaMax | 실수 | 전용면적 범위 (㎡) |

### 1.2 지도 탐색 단계별 호출

지도 탐색은 단계에 따라 호출하는 엔드포인트가 다르다. 서울 전체 단계에서는 개별 매물을 조회하지 않고 자치구 집계만 사용한다. 매물 마커 조회는 자치구가 선택된 이후에만 수행한다.

| 단계 | 호출 | 표시 |
| --- | --- | --- |
| 서울 전체 | GET /api/properties/district-counts | 자치구별 매물 수와 위험 등급 분포. 개별 매물 마커는 표시하지 않는다 |
| 자치구 선택 | GET /api/properties/map-clusters (district + 표시 영역) | 해당 구의 매물을 격자로 묶은 묶음과 개별 마커(1.12). 지도 이동·확대 시 표시 영역 좌표를 갱신해 재호출한다 |
| 매물 선택 | GET /api/properties/{propertyId} | 매물 상세 |

필터를 변경하면 현재 단계에 해당하는 엔드포인트를 다시 호출한다. 두 엔드포인트가 동일한 필터 파라미터를 사용하므로 단계를 오갈 때 조건이 그대로 유지된다.

### 1.3 매물 조회 응답 형태

매물 조회는 단일 엔드포인트로 제공하며, 반경 조건의 유무로 응답 형태가 결정된다.

| 구분 | 추가 파라미터 | 응답 |
| --- | --- | --- |
| 목록 | sort (보증금·전세가율·등록일 기준), cursor, size | 요약 필드를 포함한 매물 목록. 커서 페이지네이션 적용. 정렬·비교, 상담 결과 표시에 사용한다 |
| 지도 마커 | 중심 좌표와 반경(lat, lng, radiusKm). 셋 모두 필요하다. `radiusKm`은 0 초과 2 이하(잠정 — 쓰는 곳은 부하 스크립트의 동 단위 1 km이고, 자치구 규모는 1.12 묶음 조회의 몫이다) | 마커 표시와 선택 시 미리보기에 필요한 필드만 포함한다. 세부 구성은 1.4절을 따른다. 반경 안 전체를 그려야 하므로 커서 페이지네이션을 사용하지 않는다 |

- 반경 조건은 바운딩 박스로 1차 필터한 뒤 거리 계산으로 확정하며, 거리순으로 정렬한다.
- 표시 영역 좌표(minLat · maxLat · minLng · maxLng)가 하나라도 오면 `INVALID_REQUEST`(400)다. 자치구 단계의 표시 영역 조회는 1.12 묶음 조회를 쓴다.
- 반경 조건이 없으면 목록 형태로 응답한다. 정렬 기준을 지정하지 않으면 등록일 내림차순을 적용한다.

### 1.4 지도 마커 응답

마커에 직접 표시하는 값과 마커 선택 시 미리보기에 표시하는 값을 함께 반환한다. 미리보기를 위해 추가 호출을 하지 않기 위함이며, 판정 근거와 3사 판정 결과는 포함하지 않고 상세 조회에서 제공한다.

| 필드 | 표시 위치 | 설명 |
| --- | --- | --- |
| propertyId | — | 매물 식별자 |
| latitude / longitude | — | 마커 좌표 |
| deposit | 마커 | 보증금 (원). 마커에 억 단위로 축약 표기한다 |
| riskGrade | 마커 | 위험 등급. 마커 색으로 구분한다 |
| contractType | 미리보기 | 계약유형 |
| monthlyRent | 미리보기 | 월세 (원). 전세는 0 |
| district | 미리보기 | 자치구명 |
| debtRatio | 미리보기 | 전세가율 (%) |
| hasSeniorDebt | 미리보기 | 선순위채권 존재 여부 |

- 판정 근거 수치, 보증보험 3사 판정, 등기 이력은 마커 응답에 포함하지 않는다. 미리보기에서 상세로 진입할 때 위험도 조회와 매물 상세 조회로 가져온다.
- 마커 응답의 위험 등급과 전세가율은 최신 분석 결과를 사용한다. 분석 이력이 없는 매물은 등급을 미분석으로 표기한다.
탐색 동작과 호출 시점

| 동작 | 결과 | 호출 |
| --- | --- | --- |
| 마커에 포인터를 올림 | 미리보기 카드 표시 | 없음. 마커 응답에 포함된 값을 사용한다 |
| 마커 선택 | 미리보기 카드 고정 | 없음 |
| 카드의 상세 보기 선택 | 지도 옆 패널에 상세 표시 | 매물 상세 조회, 위험도 조회 |
| 카드 외부 선택 | 카드 닫힘 | 없음 |

GET /api/properties?district=강서구&contractType=DEPOSIT_ONLY&lat=37.55&lng=126.85&radiusKm=1 — 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "propertyId": 1024,
        "latitude": 37.5501234,
        "longitude": 126.8497561,
        "deposit": 230000000,
        "riskGrade": "SAFE",
        "contractType": "DEPOSIT_ONLY",
        "monthlyRent": 0,
        "district": "강서구",
        "debtRatio": 68.0,
        "hasSeniorDebt": true
      }
    ],
    "count": 1
  }
}
```

- 포인터 동작이 없는 환경에서는 선택으로 미리보기 카드를 표시한다. 두 경우 모두 상세 진입은 카드 내 상세 보기 선택으로만 이루어진다.
- 상세는 별도 화면으로 이동하지 않고 지도 옆 패널에 표시한다. 지도의 위치·확대 수준·필터 상태를 유지하여 여러 매물을 연달아 비교할 수 있게 하기 위함이다.

### 1.5 자치구 집계 응답

동일한 필터 조합에 대한 응답은 캐싱하여 재집계하지 않는다. 응답에 집계 기준 시각을 포함한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| districts[].name | 문자열 | 자치구명 |
| districts[].count | 정수 | 조건에 해당하는 매물 수 |
| districts[].gradeCounts | 객체 | 위험 등급별 건수 |
| totalCount | 정수 | 전체 합계 |
| aggregatedAt | 일시 | 집계 기준 시각 |

GET /api/properties/district-counts?contractType=DEPOSIT_ONLY&depositMax=300000000 — 응답

```json
{
  "success": true,
  "data": {
    "districts": [
      {
        "name": "강서구",
        "count": 290,
        "gradeCounts": { "SAFE": 180, "CAUTION": 82, "DANGER": 28 }
      },
      {
        "name": "구로구",
        "count": 250,
        "gradeCounts": { "SAFE": 150, "CAUTION": 70, "DANGER": 30 }
      }
    ],
    "totalCount": 540,
    "aggregatedAt": "2026-07-29T10:05:00+09:00"
  }
}
```

### 1.6 목록 조회 응답

GET /api/properties?district=강서구&contractType=DEPOSIT_ONLY&sort=deposit,asc&size=20

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "propertyId": 1024,
        "district": "강서구",
        "address": "서울특별시 강서구 화곡로 123",
        "propertyType": "APARTMENT",
        "contractType": "DEPOSIT_ONLY",
        "deposit": 230000000,
        "monthlyRent": 0,
        "areaSqm": 42.5,
        "floor": 3,
        "riskGrade": "SAFE",
        "debtRatio": 68.0,
        "registeredAt": "2026-07-20T14:03:00+09:00"
      }
    ],
    "nextCursor": "eyJpZCI6MTAyNH0",
    "hasNext": true
  }
}
```

### 1.7 매물 상세 응답

GET /api/properties/1024

```json
{
  "success": true,
  "data": {
    "propertyId": 1024,
    "district": "강서구",
    "address": "서울특별시 강서구 화곡로 123",
    "latitude": 37.5501234,
    "longitude": 126.8497561,
    "propertyType": "APARTMENT",
    "contractType": "DEPOSIT_ONLY",
    "deposit": 230000000,
    "monthlyRent": 0,
    "areaSqm": 42.5,
    "floor": 3,
    "landlordName": "김임대",
    "marketPrice": 340000000,
    "priceType": "ACTUAL_TRANSACTION",
    "priceDate": "2026-06-30",
    "riskSummary": {
      "riskGrade": "SAFE",
      "debtRatio": 68.0,
      "insuranceEligible": true
    },
    "wishlisted": false,
    "registeredAt": "2026-07-20T14:03:00+09:00"
  }
}
```

### 1.8 건축물대장 응답

```json
{
  "success": true,
  "data": {
    "propertyId": 1024,
    "mainPurpose": "공동주택",
    "isResidential": true,
    "violationBuilding": false,
    "totalFloorArea": 480.2,
    "exclusiveArea": 42.5,
    "approvalDate": "2015-04-18",
    "collectedAt": "2026-07-28T02:10:00+09:00"
  }
}
```

### 1.9 관심 매물

- 등록 — 없는 매물이면 404 `PROPERTY_NOT_FOUND`, 이미 등록한 매물이면 409 `WISHLIST_DUPLICATED`. 성공은 201
- 해제 — 등록되지 않은 매물이어도 204. DELETE 는 멱등이다
- 목록 파라미터 — `cursor` · `size`(공통 규약 1.4)
- 정렬은 등록 역순(최근 등록 먼저)
- `riskGrade` — 최신 분석의 등급. 아직 분석되지 않은 매물은 `null`
- `previousGrade` — 최신 분석의 직전 등급. 분석 전이거나 첫 분석이면 `null`

POST /api/me/wishlist — 요청

```json
{
  "propertyId": 1024
}
```

GET /api/me/wishlist — 응답

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "propertyId": 1024,
        "district": "강서구",
        "deposit": 230000000,
        "riskGrade": "SAFE",
        "previousGrade": "CAUTION",
        "addedAt": "2026-07-25T11:20:00+09:00"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### 1.10 지역 시세 통계 응답

GET /api/regions/강서구/stats?contractType=DEPOSIT_ONLY&months=12

```json
{
  "success": true,
  "data": {
    "district": "강서구",
    "contractType": "DEPOSIT_ONLY",
    "series": [
      { "period": "2026-06", "avgDeposit": 228000000, "dealCount": 142, "changeRate": -1.2 },
      { "period": "2026-07", "avgDeposit": 231000000, "dealCount": 118, "changeRate": 1.3 }
    ],
    "aggregatedAt": "2026-07-29T04:00:00+09:00"
  }
}
```

### 1.11 전월세 전환 계산

POST /api/tools/rent-conversion — 요청

```json
{
  "deposit": 200000000,
  "monthlyRent": 0,
  "targetDeposit": 100000000,
  "conversionRate": 5.5
}
```

응답

```json
{
  "success": true,
  "data": {
    "convertedDeposit": 100000000,
    "convertedMonthlyRent": 458000,
    "appliedRate": 5.5,
    "legalMaxRate": 5.5,
    "conventionalMonthlyRent": 1000000
  }
}
```

### 1.12 지도 묶음 응답

GET /api/properties/map-clusters — 자치구 단계(1.2)의 호출이다. 표시 영역의 매물을 **서버가 격자로 묶어** 돌려준다. 마커 전량을 내려보내 화면이 묶던 방식은 매물이 많은 구에서 응답이 수천 건 · 1 MB를 넘어 지도 조회가 가장 먼저 처리 한계에 닿았다(2026-09-26 부하 시험 · 용량 산정 리포트).

- 파라미터: 공통 검색 필터(1.1) + 표시 영역 `minLat` · `maxLat` · `minLng` · `maxLng` — **넷 모두 필수**다. 반경 조건은 받지 않는다. 하나라도 없거나 `min`이 `max`보다 크면 `INVALID_REQUEST`(400)
- 표시 영역을 **12 × 12 칸**으로 나눈다. 칸 번호는 `floor((좌표 − 최소) ÷ 칸 크기)`이고 0 ~ 11로 자른다 — 경계에 걸린 매물은 가장자리 칸에 든다. 영역의 높이나 폭이 0이면(`min` = `max`) 그 축은 모두 0번 칸이다
- 영역의 매물이 **40건 이하면 묶지 않는다**(`clustered: false`) — 전부 `markers`로 온다
- 40건을 넘으면 **두 건 이상인 칸은 묶음**(`clusters`), **한 건뿐인 칸은 개별 마커**(`markers`)로 온다
- 묶음을 선택하면 그 칸 경계(`minLat` ~ `maxLng`)로 지도를 확대하고, 바뀐 표시 영역으로 이 조회를 다시 부른다

| 필드 | 설명 |
| --- | --- |
| total | 표시 영역 안 매물 수(필터 적용) |
| clustered | 격자로 묶었는지 |
| clusters[].key | 칸 식별자 `행:열`. 같은 표시 영역 안에서만 유일하다 |
| clusters[].latitude / longitude | 칸 안 매물의 평균 좌표 — 묶음 표시 위치 |
| clusters[].count | 칸 안 매물 수 |
| clusters[].gradeCounts | 등급별 매물 수 `SAFE` · `CAUTION` · `DANGER` · `UNANALYZED`(분석 이력 없음) |
| clusters[].minLat · maxLat · minLng · maxLng | 칸 경계 — 선택 시 확대할 영역 |
| markers[] | 개별 마커. 필드는 지도 마커 응답(1.4)과 같다 |

GET /api/properties/map-clusters?district=강서구&minLat=37.52&maxLat=37.58&minLng=126.81&maxLng=126.89 — 응답(일부)

```json
{
  "success": true,
  "data": {
    "total": 6003,
    "clustered": true,
    "clusters": [
      {
        "key": "5:7",
        "latitude": 37.5476,
        "longitude": 126.8601,
        "count": 214,
        "gradeCounts": { "SAFE": 80, "CAUTION": 71, "DANGER": 58, "UNANALYZED": 5 },
        "minLat": 37.545,
        "maxLat": 37.55,
        "minLng": 126.8567,
        "maxLng": 126.8633
      }
    ],
    "markers": [
      {
        "propertyId": 41408,
        "latitude": 37.5791,
        "longitude": 126.8104,
        "deposit": 150000000,
        "riskGrade": "CAUTION",
        "contractType": "DEPOSIT_ONLY",
        "monthlyRent": 0,
        "district": "강서구",
        "debtRatio": 74.5,
        "hasSeniorDebt": false
      }
    ]
  }
}
```

