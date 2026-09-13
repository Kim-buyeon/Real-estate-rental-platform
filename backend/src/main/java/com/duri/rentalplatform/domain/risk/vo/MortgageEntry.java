package com.duri.rentalplatform.domain.risk.vo;

/**
 * 깡통전세 판정의 입력인 을구 한 행. 엔티티를 직접 받지 않아 계산기가 JPA 에 묶이지 않는다.
 *
 * @param maxBondAmount       채권최고액(원) — {@code mortgage_history.max_bond_amount}
 * @param priorTenantDeposit  선순위 임차보증금(원). 없으면 {@code null} — {@code mortgage_history.prior_tenant_deposit}
 * @param senior              임차인보다 순위가 앞서는가 — {@code mortgage_history.senior_debt_yn}
 * @param active              말소되지 않았는가 — {@code mortgage_history.is_active}
 */
public record MortgageEntry(
        long maxBondAmount,
        Long priorTenantDeposit,
        boolean senior,
        boolean active
) {
}
