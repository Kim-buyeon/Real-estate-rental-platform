package com.duri.rentalplatform.domain.notification.enums;

/**
 * 알림 유형 — {@code notification.notif_type}. API 명세서(알림) 1.3 목록 응답 · 1.4 실시간 이벤트의 {@code type} 값이다.
 *
 * <p>1단계 트리거는 위험도 변경 · 등기 변동 둘이다(비즈니스 로직 정의서 7장). 신규 매물 · 금리 변동 · 상담 일정은 차기 범위라
 * 트리거가 붙을 때 더한다.
 */
public enum NotificationType {
    RISK_CHANGE,
    REGISTRY_CHANGE
}
