package com.duri.rentalplatform.domain.notification.vo;

import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.property.enums.ContractType;

/**
 * 구독 설정 매퍼 행. 신규 매물은 자치구마다 한 행이라 응답(API 명세서(알림) 1.2)의 {@code districts[]} 로 서비스가 묶는다.
 *
 * @param targetDistrict 신규 매물 외 유형이거나 자치구 없이 끈 신규 매물이면 null
 * @param contractType null 이면 계약 유형 전체
 * @param depositMax null 이면 상한 없음
 */
public record NotificationSubscriptionRow(
        SubscriptionType subscriptionType,
        String targetDistrict,
        ContractType contractType,
        Long depositMax,
        boolean active
) {
}
