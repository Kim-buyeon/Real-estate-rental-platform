package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import java.util.List;

/**
 * 권리 침해 검출(RISK-03) 결과. 두 목록 모두 중복이 없고 {@link OwnershipRightType} 선언 순이다.
 *
 * @param rightViolations 유효한 권리 침해 등기 목적 — 위험도 응답 {@code rightViolations}
 * @param warnings        유효한 경고 등기 목적 — 위험도 응답 {@code warnings}
 */
public record RightViolationResult(
        List<OwnershipRightType> rightViolations,
        List<OwnershipRightType> warnings
) {

    public RightViolationResult {
        rightViolations = List.copyOf(rightViolations);
        warnings = List.copyOf(warnings);
    }
}
