package com.duri.rentalplatform.external.realestate;

import java.time.YearMonth;

/**
 * 매매 실거래가 조회 조건. 전월세와 같이 <b>법정동 코드 앞 5자리(시군구 코드)와 계약년월(YYYYMM)</b> 두 값으로만 조회한다.
 *
 * @param lawdCode          법정동 코드 앞 5자리. API 파라미터 {@code LAWD_CD}
 * @param contractYearMonth 계약년월. API 파라미터 {@code DEAL_YMD} 에 YYYYMM 으로 넣는다
 * @param buildingType      호출할 서비스 구분
 */
public record SaleTransactionQuery(
        String lawdCode,
        YearMonth contractYearMonth,
        SaleBuildingType buildingType
) {
}
