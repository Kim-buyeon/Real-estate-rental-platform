package com.duri.rentalplatform.domain.notification.dto.response;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import java.util.List;

/** {@code GET · PUT /api/me/notification-subscriptions} 응답 — API 명세서(알림) 1.2. */
public record NotificationSubscriptionResponse(
        NewProperty newProperty,
        Toggle rateChange,
        Toggle wishlistMonitoring,
        Toggle consultSchedule
) {

    /** 신규 매물. 조건은 활성 여부와 무관하게 저장된 값이다 — 꺼 둔 설정을 다시 켤 때 화면이 조건을 잃지 않는다. */
    public record NewProperty(boolean enabled, Conditions conditions) {
    }

    /**
     * @param districts 저장 순서. 없으면 빈 배열
     * @param contractType null 이면 계약 유형 전체
     * @param depositMax null 이면 상한 없음
     */
    public record Conditions(List<String> districts, ContractType contractType, Long depositMax) {
    }

    /** 조건 없는 유형의 수신 여부. */
    public record Toggle(boolean enabled) {
    }
}
