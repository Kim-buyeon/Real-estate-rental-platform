package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;

/**
 * 갑구 한 행. 권리 침해 검출(RISK-03)과 명의 정합 확인(RISK-04)의 입력이다. 엔티티를 직접 받지 않아 계산기가 JPA 에
 * 묶이지 않는다.
 *
 * @param rankNo     순위번호 — {@code ownership_history.rank_no}. 현재 소유자를 고르는 순서다
 * @param rightType  등기 목적
 * @param holderName 권리자 — {@code ownership_history.owner_name}. 소유권 등기면 소유자
 * @param current    말소되지 않았는가 — {@code ownership_history.is_current}
 */
public record OwnershipRightEntry(
        int rankNo,
        OwnershipRightType rightType,
        String holderName,
        boolean current
) {
}
