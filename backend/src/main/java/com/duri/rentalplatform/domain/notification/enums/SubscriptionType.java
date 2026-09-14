package com.duri.rentalplatform.domain.notification.enums;

/**
 * 알림 구독 유형 — {@code notification_subscription.subscription_type}. API 명세서(알림) 1.2 의 네 항목과 짝이다.
 *
 * <p>신규 매물만 조건(자치구 · 계약 유형 · 보증금 상한)을 갖고, 나머지는 수신 여부만 갖는다 — 기능 정의서 NOTI-01.
 */
public enum SubscriptionType {
    NEW_PROPERTY,
    RATE_CHANGE,
    WISHLIST_MONITORING,
    CONSULT_SCHEDULE
}
