package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExternalApiPropertiesTest {

    @Test
    @DisplayName("전월세가 real 인데 매매가 mock 이면 기동을 멈춘다 — 실매물 시세가 Mock 으로 덮이는 조합")
    void rejectsRealRentWithMockSale() {
        assertThatThrownBy(() -> new ExternalApiProperties(settings("real"), null, null, null, settings("mock"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sale-transaction");
    }

    @Test
    @DisplayName("둘 다 real 이거나 둘 다 mock 이면 뜬다")
    void acceptsMatchingModes() {
        assertThatCode(() -> new ExternalApiProperties(settings("real"), null, null, null, settings("real"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ExternalApiProperties(settings("mock"), null, null, null, settings("mock"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전월세가 mock 이면 매매만 real 이어도 뜬다 — 매물이 가짜라 덮일 실시세가 없다")
    void acceptsMockRentWithRealSale() {
        assertThatCode(() -> new ExternalApiProperties(settings("mock"), null, null, null, settings("real"), null))
                .doesNotThrowAnyException();
    }

    private static ExternalApiProperties.ClientSettings settings(String mode) {
        return new ExternalApiProperties.ClientSettings(mode, null, null, null, null, null);
    }
}
