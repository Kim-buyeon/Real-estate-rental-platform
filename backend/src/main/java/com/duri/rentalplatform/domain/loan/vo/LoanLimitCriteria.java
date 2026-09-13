package com.duri.rentalplatform.domain.loan.vo;

import java.math.BigDecimal;

/**
 * 한도 계산의 기준값. 규제는 {@code loan_regulation}, 금리 · 상품 한도는 {@code loan_product} 에서 호출부가 읽어 넘긴다.
 *
 * @param depositRatioLimit    임차보증금 대비 비율(%)
 * @param guaranteeCapNoHouse  보증기관 상한 — 무주택(원)
 * @param guaranteeCapOneHouse 보증기관 상한 — 주택 보유(원)
 * @param dsrLimit             DSR 한도(%)
 * @param stressDsrRate        스트레스 금리 가산율(%p)
 * @param interestRate         상품 금리(%)
 * @param productMaxLimit      상품 한도(원)
 */
public record LoanLimitCriteria(
        BigDecimal depositRatioLimit,
        long guaranteeCapNoHouse,
        long guaranteeCapOneHouse,
        BigDecimal dsrLimit,
        BigDecimal stressDsrRate,
        BigDecimal interestRate,
        long productMaxLimit
) {
}
