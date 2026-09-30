package com.duri.rentalplatform.external.loanrate;

import java.math.BigDecimal;

/**
 * 은행 하나의 한 달치 전세자금대출 금리. 제공처 응답을 우리 형태로 옮긴 것이다.
 *
 * @param bankName            은행명
 * @param weightedAverageRate 가중평균 대출금리(%). 제공처 {@code avgLoanRat2}
 * @param loanAmount          대출실행금액(원). 제공처 {@code loanAmt}
 */
public record BankLoanRate(String bankName, BigDecimal weightedAverageRate, long loanAmount) {
}
