package com.duri.rentalplatform.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.enums.WishlistChangeType;
import com.duri.rentalplatform.domain.notification.service.NotificationCommandService;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.event.RegistryChangedEvent;
import com.duri.rentalplatform.domain.risk.event.RiskGradeChangedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RiskEventListener} — 이벤트를 변동 유형 · 변동 전후 값으로 옮기고, 생성 실패를 삼킨다.
 *
 * <p>커밋 이후에만 받는지와 비동기 실행은 프록시가 거는 것이라 통합 테스트({@code NotificationCreationIntegrationTest})가 본다.
 */
class RiskEventListenerTest {

    private static final long PROPERTY_ID = 1024L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 14, 3, 0);

    private NotificationCommandService service;
    private RiskEventListener listener;

    @BeforeEach
    void setUp() {
        service = mock(NotificationCommandService.class);
        listener = new RiskEventListener(service);
    }

    @Test
    @DisplayName("위험 등급 변경은 RISK_GRADE 이고 변동 전 · 후 값은 등급 상수명이다")
    void riskGradeChanged() {
        listener.onRiskGradeChanged(new RiskGradeChangedEvent(PROPERTY_ID, RiskGrade.CAUTION, RiskGrade.DANGER, AT));

        verify(service).createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");
    }

    @Test
    @DisplayName("등기 변동은 REGISTRY 이고 변동 전 · 후 값은 이벤트의 유효 건수 요약이다")
    void registryChanged() {
        listener.onRegistryChanged(new RegistryChangedEvent(PROPERTY_ID, "갑구 2 · 을구 1", "갑구 2 · 을구 0", AT));

        verify(service).createForWishlist(PROPERTY_ID, WishlistChangeType.REGISTRY, "갑구 2 · 을구 1", "갑구 2 · 을구 0");
    }

    @Test
    @DisplayName("생성이 실패해도(락 대기 초과 등) 예외를 올리지 않는다 — 판정은 이미 커밋되었다")
    void swallowsCreationFailure() {
        when(service.createForWishlist(any(), any(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatCode(() -> listener.onRiskGradeChanged(
                new RiskGradeChangedEvent(PROPERTY_ID, RiskGrade.SAFE, RiskGrade.CAUTION, AT)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onRegistryChanged(
                new RegistryChangedEvent(PROPERTY_ID, "갑구 1 · 을구 0", "갑구 1 · 을구 1", AT)))
                .doesNotThrowAnyException();
    }
}
