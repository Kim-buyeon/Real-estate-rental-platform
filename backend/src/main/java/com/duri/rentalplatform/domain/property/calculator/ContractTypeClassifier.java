package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.enums.ContractType;

/**
 * 보증금과 월세로 계약 유형을 가른다.
 *
 * <p>실거래가 자료에는 계약 유형 컬럼이 없다. 보증금과 월세 두 숫자만 온다. 전세와 월세는 월세가
 * 0인지로 바로 갈리지만, 반전세는 경계를 정해야 한다.
 *
 * <p><b>경계의 근거</b> — 한국부동산원 임대차 통계의 구분을 따랐다. 보증금이 월세의 240개월치를
 * 넘으면 준전세로 분류한다. 240은 임의의 수가 아니라 그 통계가 쓰는 경계다.
 * <b>이 값은 아직 어느 설계 문서에도 없다 — 미확정으로 두고, 「비즈니스 로직 정의서」에 확정되면
 * 그 값을 따른다.</b> 판정 임계값이 아니라 적재 시 분류 기준이라 기준 테이블로 빼지 않았다.
 */
public final class ContractTypeClassifier {

    /** 준전세(반전세) 경계. 보증금 ÷ 월세 가 이 값을 넘으면 반전세다. */
    private static final long SEMI_DEPOSIT_MONTHS = 240L;

    public static ContractType classify(long deposit, long monthlyRent) {
        if (monthlyRent <= 0L) {
            return ContractType.DEPOSIT_ONLY;
        }
        if (deposit > monthlyRent * SEMI_DEPOSIT_MONTHS) {
            return ContractType.SEMI_DEPOSIT;
        }
        return ContractType.MONTHLY_RENT;
    }

    private ContractTypeClassifier() {
    }
}
