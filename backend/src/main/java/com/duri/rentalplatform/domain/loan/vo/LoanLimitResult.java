package com.duri.rentalplatform.domain.loan.vo;

import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import java.math.BigDecimal;

/**
 * 한도 계산 결과. 금액은 원 단위 버림.
 *
 * @param dsrLimit       주택 보유자만. 무주택이면 null
 * @param stressDsrLimit 주택 보유자만, 참고. 최종 한도에 넣지 않는다. 무주택이면 null
 * @param dtiReference   참고 DTI(%, 소수 첫째 자리 HALF_UP). 연소득 0 이면 null
 */
public record LoanLimitResult(
        long depositLimit,
        long guaranteeCapLimit,
        Long dsrLimit,
        Long stressDsrLimit,
        long finalLimit,
        AppliedRegulation appliedRegulation,
        BigDecimal dtiReference
) {
}
