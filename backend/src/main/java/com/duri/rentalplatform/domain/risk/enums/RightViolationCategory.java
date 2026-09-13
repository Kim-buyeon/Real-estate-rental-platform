package com.duri.rentalplatform.domain.risk.enums;

/**
 * 갑구 등기 목적의 권리 침해 검출(RISK-03) 분류. {@link OwnershipRightType} 이 하나씩 갖는다.
 *
 * <p>검출기가 이 값을 switch 식으로 가른다. {@code default} 없이 쓰므로 값을 더하면 컴파일이 멈춘다.
 */
public enum RightViolationCategory {
    /** 권리 침해 — 위험도 응답 {@code rightViolations} 에 들어간다. */
    VIOLATION,
    /** 경고 — 위험도 응답 {@code warnings} 에 들어간다. */
    WARNING,
    /** 해당 없음 — 소유권 등기처럼 침해도 경고도 아닌 것. */
    NONE
}
