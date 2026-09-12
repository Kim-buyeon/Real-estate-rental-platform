package com.duri.rentalplatform.external.realestate;

import java.time.YearMonth;

/**
 * 전월세 실거래가 조회 조건.
 *
 * <p>이 API 는 <b>법정동 코드 앞 5자리(시군구 코드)와 계약년월(YYYYMM)</b> 두 값으로만 조회한다.
 * 기간 범위나 단지 단위 조회가 없으므로, 여러 달을 받으려면 달 수만큼 호출해야 한다.
 *
 * @param lawdCode          법정동 코드 앞 5자리. API 파라미터 {@code LAWD_CD}
 * @param contractYearMonth 계약년월. API 파라미터 {@code DEAL_YMD} 에 YYYYMM 으로 넣는다
 * @param buildingType      호출할 서비스 구분
 */
public record RentTransactionQuery(
        String lawdCode,
        YearMonth contractYearMonth,
        RentBuildingType buildingType
) {
}
