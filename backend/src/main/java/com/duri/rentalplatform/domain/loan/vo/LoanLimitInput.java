package com.duri.rentalplatform.domain.loan.vo;

/**
 * 한도 계산의 사용자 · 매물 입력.
 *
 * @param hasHouse                  주택 보유 여부. true 를 1주택자로 본다
 * @param annualIncome              연소득(원). 미입력은 0
 * @param existingLoanAnnualPayment 기존 대출 연 상환액(원). 미입력은 0
 * @param deposit                   임차보증금(원)
 */
public record LoanLimitInput(boolean hasHouse, long annualIncome, long existingLoanAnnualPayment, long deposit) {
}
