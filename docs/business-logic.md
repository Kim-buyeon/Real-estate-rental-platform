# 전월세 부동산 금융 플랫폼 — 비즈니스 로직 정의서

> 애플리케이션 처리 로직 명세
> 보험 가입 판정 · 위험 등급 · 깡통전세 · 대출 추천 · 알림
>
> ※ DB에는 판정 결과만 저장하며, 아래 계산 로직은 Spring Boot 애플리케이션에서 처리
> ※ 로직은 구현 시점과 무관하게 모두 정의한다. **차기 범위는 표시만 하며, 착수 순서는 `docs/roadmap.md`를 따른다.**
> 작성 기준일 : 2026년 8월

---

## 1. 개요

본 문서는 전월세 부동산 금융 플랫폼이 제공하는 5대 핵심 기능을 구현하기 위한 비즈니스 로직을 정의한다. 데이터베이스 설계서가 '무엇을 저장하는가'를 다룬다면, 본 문서는 '저장된 데이터로 무엇을 계산하고 판정하는가'를 다룬다.

**핵심 원칙**: 모든 계산·판정 로직은 애플리케이션 계층(Spring Boot)에서 수행하고, 데이터베이스에는 그 결과만 저장한다. 예를 들어 위험 등급 판정 규칙은 코드로 구현되며, DB의 RISK_ANALYSIS 테이블에는 판정 결과(risk_grade)만 기록된다.

### 비즈니스 로직 구성

| No | 로직 | 관련 기능 | 입력 → 출력 | 범위 |
|---|---|---|---|---|
| 1 | 보증보험 가입 판정 | 위험도 분석 · 대출 추천 전제 | 등기·대장 → 가입 가능 여부 | 1단계 (RISK-05) |
| 2 | 위험 등급 판정 | 전세 위험도 분석 (기능 4) | 가입여부+전세가율 → 등급 | 1단계 (RISK-01) |
| 3 | 깡통전세 계산 | 위험도 분석 보조 지표 | 채권최고액+보증금+시세 → 위험 | 1단계 (RISK-02) |
| 4 | 맞춤형 대출 추천 | 대출 상품 추천 (기능 2) | 사용자 자산 → 추천 목록 | **차기 (LOAN-03)** |
| 5 | 대출 한도 계산 | 대출 계획 수립 (기능 3) | LTV·DSR·Stress DSR → 한도 | 1단계 (LOAN-01) |
| 6 | 실시간 알림 트리거 | 실시간 알림 (기능 5) | 이벤트 감지 → 알림 발송 | 1단계 (NOTI-02) · 5종 중 2종 |

---

## 2. 보증보험 가입 판정 로직

우리 시스템에서 '안전한 집'의 정의는 **보증보험 가입이 가능한 집**이다. 보증보험사(HUG·HF·SGI)는 자기 자본으로 보증을 서기 때문에, 위험한 집은 가입을 거절한다. 따라서 3사 중 하나라도 가입 가능하면 그 집은 일정 수준 이상의 안전성이 검증된 것으로 본다.

**중요**: 보증보험 가입 조건은 '집 단위 조건'과 '개인 자격 조건'으로 나뉜다. 우리 시스템은 집 단위 조건만 판정하며, 개인 자격 조건은 실제 가입 단계에서 보증기관이 확인하므로 참고사항으로만 안내한다. 같은 집이라도 개인의 소득·계약시점에 따라 최종 가입 여부가 달라질 수 있기 때문에, 매물의 안전도와 개인의 가입 자격을 분리하여 사용자 혼동을 방지한다.

### 판정 범위 구분

| 구분 | 내용 |
|---|---|
| 집 단위 조건 (시스템 판정) | 전세가율 · 보증금 한도 · 위반건축물 · 권리침해 · 명의일치 · 주택유형 |
| 개인 자격 조건 (가입 시 확인) | 연소득 기준 · 신청기한(계약기간 1/2) · 신규/갱신 구분 · 주거용 표기 확인 |

### 판정 절차

각 보증기관(HUG·HF·SGI)에 대해 다음 조건을 **모두** 만족하는지 검사한다. 하나라도 위배되면 해당 기관은 가입 불가로 판정한다.

- 전세가율 조건: (선순위채권 합계 + 전세보증금) ÷ 주택가액 ≤ 기관별 담보인정비율(통상 90%)
- 보증금 한도: 전세보증금 ≤ 기관별 최대 보증 가능 보증금 (HUG·HF 수도권 7억 / SGI 고액 가능)
- 주택 상태: 위반건축물이 아닐 것 (BUILDING_LEDGER.violation_yn = FALSE)
- 권리 침해 없음: 압류·가압류·경매·신탁 등기가 없을 것 (OWNERSHIP_HISTORY 확인)
- 명의 일치: 등기상 소유자 = 임대인(PROPERTY.landlord_name), 대장 주소 = 등기 주소
- 대항력 요건: 전입신고 + 확정일자 가능할 것 (3사 공통)

HF는 추가로 전세자금보증부 대출과 연계되는 경우만 가입 가능하다는 고유 조건이 있다.

### 판정 의사코드

```
// 각 기관별 가입 가능 여부 판정
function checkEligibility(property, registry, ledger, criteria):
    debtRatio = (선순위채권합계 + 전세보증금) / 주택가액

    if debtRatio > criteria.담보인정비율:        return false
    if 전세보증금 > criteria.최대보증금:           return false
    if ledger.violation_yn == true:              return false
    if registry.압류 or 가압류 or 경매 or 신탁:    return false
    if 등기소유자 != PROPERTY.landlord_name:        return false
    if 대장주소 != 등기주소:                        return false
    return true

// 종합 판정 → RISK_ANALYSIS에 저장
hug_eligible_yn = checkEligibility(..., HUG_CRITERIA)
hf_eligible_yn  = checkEligibility(..., HF_CRITERIA)
sgi_eligible_yn = checkEligibility(..., SGI_CRITERIA)
insurance_eligible_yn = hug OR hf OR sgi   // 하나라도 가능하면 안전
```

### 가입 시 추가 확인 사항 (개인 자격 — 시스템 미판정)

아래 조건은 매물 안전도와 무관한 개인 자격 사항으로, 실제 가입 신청 시 각 보증기관에서 확인한다. 시스템은 이를 '추가 확인 안내'로만 사용자에게 제공한다.

**3사 공통**

- 신청기한: 전세계약 기간의 1/2이 경과하기 전까지만 가입 가능
- 신규 계약: 잔금지급일과 전입신고일 중 늦은 날 ~ 계약기간 1/2 경과 전
- 갱신 계약: 갱신 전세계약서 상 계약기간의 1/2 경과 전
- 주거용 오피스텔: 전세계약서 또는 중개대상물 확인서에 '주거용' 표기 필요

**HUG (주택도시보증공사)**

- 보증료 할인 대상 소득 기준: 청년 5천만원 / 신혼부부 7.5천만원 / 일반 6천만원 이하
- 개인·법인·외국인 모두 가입 가능 (법인은 전세권 보증가입 이전 필요)

**HF (한국주택금융공사)**

- 전세자금보증부 대출을 받았거나, 전세자금보증과 동시 신청하는 경우만 가입 가능
- 대출 가능 여부와 별개로 보증보험 가입 조건 충족 여부를 별도 확인 필요

**SGI (서울보증보험)**

- 공인중개사를 통한 계약일 것 (중개대상물 확인·설명서 필요)
- HUG·HF 한도를 초과하는 고액 전세에 적합 (아파트 보증한도 무제한)

---

## 3. 위험 등급 판정 로직

보증보험 가입 가능 여부를 1차 기준으로, 전세가율을 보조 기준으로 삼아 3단계 등급을 판정한다. 점수제가 아닌 규칙 기반(rule-based)으로, 판정 결과는 RISK_ANALYSIS.risk_grade에 저장한다.

### 등급 기준

| 등급 | 조건 | 의미 |
|---|---|---|
| SAFE (안전) | 보험 가입 가능 AND 전세가율 < 80% | 보증보험사가 인정한 집. 전세가율도 여유 |
| CAUTION (주의) | 보험 가입 가능 BUT 전세가율 80% 이상 | 가입은 되나 시세 하락 시 위험 여지. 사용자 인지 필요 |
| DANGER (위험) | 3사 모두 가입 불가 OR 깡통전세 해당 | 보증보험사도 거부. 전세사기·보증금 미반환 위험 높음 |

### 판정 의사코드

```
function decideRiskGrade(insurance_eligible, lease_ratio, isKkangtong):
    if isKkangtong or insurance_eligible == false:
        return 'DANGER'
    if lease_ratio >= 80:
        return 'CAUTION'
    return 'SAFE'

// 등급 변경 시 알림 트리거 (4장 깡통전세 → 6장 알림)
if new_grade != previous_grade:
    createWishlistNotification(...)   // 관심매물이면 알림
```

---

## 4. 깡통전세 계산 로직

깡통전세란 집이 경매에 넘어갔을 때 보증금을 돌려받지 못할 위험이 있는 전세를 말한다. 집을 담보로 한 채권(채권최고액)과 전세보증금의 합이 주택 시세에 근접하면, 경매 낙찰가로는 보증금 회수가 어렵다.

### 계산식

```
// 깡통전세 위험 판정식
선순위채권 = 근저당 채권최고액(max_bond_amount)
          + 선순위 임차보증금(prior_tenant_deposit)
위험금액 = 선순위채권 + 전세보증금(deposit)
기준금액 = 주택시세(market_price) × 안전기준비율

if 위험금액 > 기준금액:  깡통전세 위험 (DANGER)

// 안전기준비율: 통상 70%, HUG 보증비율 하향(2025.9)으로 60% 적용 가능
// 채권최고액은 통상 실제 대출액의 120%로 설정됨
```

**예시**: 주택시세 3억, 채권최고액 2.5억인 경우, 채권최고액만으로 이미 시세의 83%를 차지한다. 여기에 전세보증금이 더해지면 기준(70% = 2.1억)을 크게 초과하므로 깡통전세 위험으로 판정한다.

선순위채권 합계는 MORTGAGE_HISTORY에서 순위번호(priority_no)가 임차인보다 앞서고 현재 유효한(is_active = TRUE) 항목의 채권최고액(max_bond_amount)과 선순위 임차보증금(prior_tenant_deposit)을 합산하여 구한다. 다가구주택처럼 먼저 입주한 세입자가 여럿인 경우 그 보증금 합계가 선순위채권에 포함된다.

---

## 5. 맞춤형 대출 추천 로직 — 차기 범위 (LOAN-03)

대출 추천은 두 단계 관문을 거친다. 1단계는 '안전한 집인가'(매물 기준), 2단계는 '이 사용자가 받을 수 있는 대출인가'(사용자 기준)이다. 안전 판정을 통과한 집을 전제로, 사용자의 자산 조건에 맞는 대출 상품만 추천한다.

### 추천 절차

- 1단계 (매물 안전성): RISK_ANALYSIS.insurance_eligible_yn = TRUE 인 매물만 추천 대상
- 2단계 (사용자 자격): LOAN_PRODUCT의 소득조건·주택보유조건을 사용자가 충족하는지 검사
- 3단계 (한도 산정): LTV·DSR·Stress DSR 규제를 적용해 실제 대출 가능액 계산
- 4단계 (정렬): 금리 낮은 순 또는 한도 높은 순으로 정렬하여 LOAN_PLAN 생성

### 추천 의사코드

```
function recommendLoans(user, property, risk):
    // 1단계: 안전한 집만
    if risk.insurance_eligible_yn == false:
        return []   // 위험한 집은 추천 안 함

    results = []
    for loan in LOAN_PRODUCT:
        // 2단계: 사용자 자격
        if user.annual_income > loan.income_condition:  continue
        // 주택보유 조건 (무주택전용 상품 + 1주택자 → 제외)
        if loan.house_ownership_condition==false and user.has_house: continue

        // 3단계: 규제 적용 한도
        limit = calcLoanLimit(user, property, loan)
        if limit > 0:
            results.add({loan, limit})

    return sortBy(results, 금리오름차순)   // 4단계
```

---

## 6. 대출 한도 계산 로직 (LTV · DSR · Stress DSR)

대출 한도는 세 가지 규제를 모두 적용한 뒤, **가장 낮은 값**으로 결정된다. 각 규제 기준값은 LOAN_REGULATION 테이블에 저장되며, 계산 결과는 LOAN_PLAN에 기록된다.

### 세 규제의 의미

| 규제 | 기준 | 계산 |
|---|---|---|
| LTV | 주택가격 대비 대출 비율 | 대출가능액 = 주택가격 × LTV한도 |
| DSR | 연소득 대비 원리금상환 비율 | 연원리금 ÷ 연소득 ≤ DSR한도(40%) |
| Stress DSR | 미래 금리상승 가정한 DSR | 금리에 스트레스 가산율 더해 DSR 재계산 |

### 계산 의사코드

```
function calcLoanLimit(user, property, loan):
    reg = LOAN_REGULATION[property.region_type]

    // LTV 한도
    ltvLimit = property.market_price × reg.ltv_limit

    // DSR 한도 (연소득 기준 역산)
    maxAnnualPayment = user.annual_income × reg.dsr_limit
    dsrLimit = 역산(maxAnnualPayment, loan.interest_rate)

    // Stress DSR (금리 + 가산율로 더 보수적)
    stressRate = loan.interest_rate + reg.stress_dsr_rate
    stressLimit = 역산(maxAnnualPayment, stressRate)

    // 최종: 세 한도 중 최솟값
    return min(ltvLimit, dsrLimit, stressLimit)
```

DTI는 참고용 지표로만 활용하며 실제 한도 결정에는 사용하지 않는다. 현행 규제가 DSR·Stress DSR 중심으로 운영되기 때문이다.

---

## 6-1. DSR 상세 계산식 (상환방식별)

DSR의 분자인 '연간 상환액(Annual Repayment)'은 대출의 상환방식에 따라 계산식이 다르다. 상환방식은 LOAN_PRODUCT.repayment_type에 저장하며, 대출 기간(loan_term)과 함께 원리금을 산출한다.

### DSR 기본식

```
DSR = 대출상환액 / 연소득

// 신규 대출 추천 시 (보유대출 + 신규대출 합산)
DSR = (보유대출 연상환액 + 신규대출 Annual Repayment) / 연소득

// 보유대출 연상환액 = USER.existing_loan_annual_payment
// 신규대출 Annual Repayment = 아래 상환방식별 계산
```

### 상환방식별 Annual Repayment (12개월 기준)

| 상환방식 (repayment_type) | 연간 상환액 계산 |
|---|---|
| 원금균등 (EQUAL_PRINCIPAL) | Σ (amount/period) + (amount − 누적상환원금) × rate — 원금 일정, 이자 체감 |
| 만기일시 (BULLET) | Σ (amount/period) × rate — 매월 이자만, 원금은 만기 일시상환 |
| 원리금균등 (EQUAL_PI) | 매월 동일액 상환 (하나은행 전월세대출·버팀목은 미적용) |

**1단계는 한도 계산 대상 상품의 상환방식 하나만 구현한다.** 3종 전체 구현은 차기 범위(LOAN-04 상환 시뮬레이션)다. DSR 분자 산출 자체는 LOAN-01에 필요하므로 본 절의 기본식과 데이터 매핑은 1단계에 포함된다.

### 계산에 필요한 데이터 매핑

| 계산 변수 | 저장 위치 | 비고 |
|---|---|---|
| amount (대출원금) | LOAN_PLAN.recommended_amount | 추천 금액 |
| rate (금리) | LOAN_PRODUCT.interest_rate | 상품 금리 |
| period (대출기간) | LOAN_PRODUCT.loan_term | 신규 추가 컬럼 |
| 상환방식 | LOAN_PRODUCT.repayment_type | 신규 추가 컬럼 |
| 보유대출 연상환액 | USER.existing_loan_annual_payment | 신규 추가 컬럼 |

위 5개 변수가 모두 데이터베이스에 확보되어 있어 상환방식별 DSR 계산이 가능하다.

---

## 7. 실시간 알림 트리거 로직

알림은 사용자의 구독 설정(NOTIFICATION_SUBSCRIPTION) 또는 관심 매물(WISHLIST) 모니터링 조건에 해당하는 이벤트가 발생할 때 발송된다. 알림 종류별 트리거 조건은 다음과 같다.

| 알림 종류 | 트리거 조건 | 대상 결정 | 범위 |
|---|---|---|---|
| 위험도 변경 | 관심 매물의 risk_grade 변경 | WISHLIST.monitoring_yn = TRUE | 1단계 |
| 등기 변동 | 관심 매물의 갑구·을구 변경 감지 | WISHLIST 모니터링 대상 | 1단계 |
| 신규 매물 | 구독 조건(지역·계약유형·보증금)에 맞는 매물 등록 | NOTIFICATION_SUBSCRIPTION 매칭 | **차기** |
| 금리 변동 | 기준금리·시장금리 변동 감지 | 금리 구독자 전체 | **차기 (LOAN-06)** |
| 상담 일정 | 예약 상담 시각 도래 전 | 해당 상담 예약자 | **차기 (CONS)** |

**5종 모두 발생 계기만 다르고 저장 구조와 전달 경로는 같다.** 1단계는 그 구조를 2종으로 증명하며, 나머지 3종은 트리거 조건만 추가하면 성립한다.

### 위험도 변경 알림 의사코드

```
// 매물 위험도 재분석 시 (배치 또는 등기 변동 감지)
newGrade = decideRiskGrade(...)
if newGrade != risk.previous_grade:
    risk.previous_grade = risk.risk_grade
    risk.risk_grade = newGrade

    // 이 매물을 관심 등록 + 모니터링 ON 한 사용자에게
    for w in WISHLIST where property_id = 매물 and monitoring_yn:
        createNotification(w.user, 'WISHLIST')
        createWishlistNotification(change_type='RISK',
            before=previous_grade, after=newGrade)
```

알림은 NOTIFICATION(공통) + 유형별 하위 테이블 구조로 저장되며, 실제 발송(푸시·문자)은 별도 메시지 시스템에서 처리한다. DB에는 발송 이력과 읽음 여부(is_read)만 기록한다.