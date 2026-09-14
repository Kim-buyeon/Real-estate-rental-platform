package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 관심 매물의 알림 조건. {@code wishlist.alert_condition} 에 상수명으로 저장한다 — 데이터베이스 설계서 23절.
 *
 * <p>1단계 관심 매물 알림 트리거는 위험도 변경 · 등기 변동 둘이다(비즈니스 로직 정의서 7장). 사용자가 조건을 고르는
 * API 는 없어 등록 시 둘 다를 뜻하는 값 하나로 저장한다. API 응답에는 나가지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum WishlistAlertCondition {
    RISK_AND_REGISTRY("위험도 변경 · 등기 변동");

    private final String label;
}
