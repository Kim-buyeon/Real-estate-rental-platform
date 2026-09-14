package com.duri.rentalplatform.domain.loan.enums;

/**
 * 최종 한도를 결정한 항목 — API 명세서(대출) 1.1 {@code appliedRegulation}. 선언 순서가 동률일 때의 우선순위다
 * (비즈니스 로직 정의서 6장 「같으면 앞 순서」).
 */
public enum AppliedRegulation {
    DEPOSIT_RATIO,
    GUARANTEE_CAP,
    DSR,
    PRODUCT_LIMIT
}
