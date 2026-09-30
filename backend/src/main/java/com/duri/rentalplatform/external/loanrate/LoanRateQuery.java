package com.duri.rentalplatform.external.loanrate;

import java.time.YearMonth;

/**
 * 은행별 금리 조회 조건.
 *
 * @param loanMonth 대출 실행 연월. 제공처의 {@code loanYm}(YYYYMM)
 * @param houseType 주택 유형
 */
public record LoanRateQuery(YearMonth loanMonth, LoanRateHouseType houseType) {
}
