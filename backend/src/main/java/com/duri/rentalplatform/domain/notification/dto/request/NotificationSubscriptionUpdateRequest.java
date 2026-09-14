package com.duri.rentalplatform.domain.notification.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code PUT /api/me/notification-subscriptions} 본문 — API 명세서(알림) 1.2. 조회 응답과 같은 구조로 전체를 전달하므로
 * 네 항목과 각 {@code enabled} 가 필수다.
 *
 * <p>자치구 개수 · 이름 · 중복은 {@code enabled} 에 따라 필수 여부가 갈려 서비스가 검증한다.
 */
public record NotificationSubscriptionUpdateRequest(
        @Valid @NotNull NewProperty newProperty,
        @Valid @NotNull Toggle rateChange,
        @Valid @NotNull Toggle wishlistMonitoring,
        @Valid @NotNull Toggle consultSchedule
) {

    /** 신규 매물. {@code enabled} 가 true 면 조건과 자치구 1개 이상이 필요하다. */
    public record NewProperty(@NotNull Boolean enabled, @Valid Conditions conditions) {
    }

    /**
     * @param districts 서울 자치구명(「강서구」). 최대 25개 · 중복 없음
     * @param contractType 없으면 계약 유형 전체
     * @param depositMax 원. 없으면 상한 없음
     */
    public record Conditions(List<String> districts, ContractType contractType, Long depositMax) {
    }

    public record Toggle(@NotNull Boolean enabled) {
    }
}
