package com.duri.rentalplatform.domain.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 관심 매물 모니터링 알림의 변동 유형 — {@code wishlist_notification.change_type}. 데이터베이스 설계서 29절.
 *
 * <p>변동 유형 하나가 알림 유형 하나에 대응한다. 공통 행의 유형을 여기서 읽어 두 값이 어긋나지 않게 한다.
 */
@Getter
@RequiredArgsConstructor
public enum WishlistChangeType {
    /** 위험 등급 변경. 변동 전 · 후 값은 등급 상수명({@code CAUTION} → {@code DANGER}). */
    RISK_GRADE(NotificationType.RISK_CHANGE),
    /** 등기 갑구 · 을구 변동. 변동 전 · 후 값은 유효 건수 요약({@code 갑구 2 · 을구 1}). */
    REGISTRY(NotificationType.REGISTRY_CHANGE);

    private final NotificationType notificationType;
}
