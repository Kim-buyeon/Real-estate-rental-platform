package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;

/**
 * 권리 침해 검출의 입력인 갑구 한 행. 엔티티를 직접 받지 않아 검출기가 JPA 에 묶이지 않는다.
 *
 * @param rightType 등기 목적
 * @param current   말소되지 않았는가 — {@code ownership_history.is_current}
 */
public record OwnershipRightEntry(
        OwnershipRightType rightType,
        boolean current
) {
}
