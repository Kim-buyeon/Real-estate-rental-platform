package com.duri.rentalplatform.domain.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 알림 유형 — {@code notification.notif_type}. API 명세서(알림) 1.3 목록 응답 · 1.4 실시간 이벤트의 {@code type} 값이다.
 *
 * <p>1단계 트리거는 위험도 변경 · 등기 변동 둘이다(비즈니스 로직 정의서 7장). 신규 매물 · 금리 변동 · 상담 일정은 차기 범위라
 * 트리거가 붙을 때 더한다. 목록의 {@code title} 은 유형별 고정 문구라 테이블에 두지 않고 여기서 갖는다(명세 1.3).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
    RISK_CHANGE("관심 매물의 위험 등급이 변경되었습니다"),
    REGISTRY_CHANGE("관심 매물의 등기에 변동이 생겼습니다");

    private final String title;
}
