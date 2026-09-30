package com.duri.rentalplatform.domain.loan.vo;

/**
 * 한 매물 유형의 대출 상품 행 반영 결과.
 *
 * @param inserted  새로 넣은 은행 수
 * @param updated   새 기준월로 고친 은행 수
 * @param unchanged 이미 같거나 더 늦은 기준월이라 두고 넘어간 은행 수
 */
public record LoanProductWriteResult(int inserted, int updated, int unchanged) {
}
