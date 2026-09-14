package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.notification.dto.response.NotificationSubscriptionResponse;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.notification.mapper.NotificationSubscriptionMapper;
import com.duri.rentalplatform.domain.notification.vo.NotificationSubscriptionRow;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NotificationSubscriptionQueryService} — 행 없는 유형의 기본값, 자치구 묶기, 비활성 조건 보존. */
class NotificationSubscriptionQueryServiceTest {

    private static final long USER_ID = 42L;

    private NotificationSubscriptionMapper mapper;
    private NotificationSubscriptionQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationSubscriptionMapper.class);
        service = new NotificationSubscriptionQueryService(mapper);
    }

    @Test
    @DisplayName("행이 없으면 관심 매물 모니터링만 켬, 나머지는 끔이고 신규 매물 조건은 빈 자치구 · null 두 값이다")
    void defaultsWhenNoRows() {
        when(mapper.selectByUser(USER_ID)).thenReturn(List.of());

        NotificationSubscriptionResponse response = service.get(USER_ID);

        assertThat(response.newProperty().enabled()).isFalse();
        assertThat(response.newProperty().conditions())
                .isEqualTo(new NotificationSubscriptionResponse.Conditions(List.of(), null, null));
        assertThat(response.rateChange().enabled()).isFalse();
        assertThat(response.wishlistMonitoring().enabled()).isTrue();
        assertThat(response.consultSchedule().enabled()).isFalse();
        assertThat(service.isWishlistMonitoringEnabled(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("신규 매물 자치구 행들을 저장 순서대로 districts 로 묶고, 저장된 유형 행은 그 값을 쓴다")
    void groupsDistricts() {
        when(mapper.selectByUser(USER_ID)).thenReturn(List.of(
                row(SubscriptionType.NEW_PROPERTY, "강서구", ContractType.DEPOSIT_ONLY, 250_000_000L, true),
                row(SubscriptionType.NEW_PROPERTY, "구로구", ContractType.DEPOSIT_ONLY, 250_000_000L, true),
                row(SubscriptionType.RATE_CHANGE, null, null, null, true),
                row(SubscriptionType.WISHLIST_MONITORING, null, null, null, false),
                row(SubscriptionType.CONSULT_SCHEDULE, null, null, null, true)));

        NotificationSubscriptionResponse response = service.get(USER_ID);

        assertThat(response.newProperty().enabled()).isTrue();
        assertThat(response.newProperty().conditions()).isEqualTo(new NotificationSubscriptionResponse.Conditions(
                List.of("강서구", "구로구"), ContractType.DEPOSIT_ONLY, 250_000_000L));
        assertThat(response.rateChange().enabled()).isTrue();
        assertThat(response.wishlistMonitoring().enabled()).isFalse();
        assertThat(response.consultSchedule().enabled()).isTrue();
        assertThat(service.isWishlistMonitoringEnabled(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("꺼 둔 신규 매물도 저장된 조건을 돌려준다")
    void keepsInactiveConditions() {
        when(mapper.selectByUser(USER_ID)).thenReturn(List.of(
                row(SubscriptionType.NEW_PROPERTY, "마포구", null, 300_000_000L, false)));

        NotificationSubscriptionResponse.NewProperty newProperty = service.get(USER_ID).newProperty();

        assertThat(newProperty.enabled()).isFalse();
        assertThat(newProperty.conditions()).isEqualTo(
                new NotificationSubscriptionResponse.Conditions(List.of("마포구"), null, 300_000_000L));
    }

    @Test
    @DisplayName("자치구 없이 꺼 둔 신규 매물 행은 districts 가 비고 계약 유형 · 상한은 남는다")
    void inactiveWithoutDistrict() {
        when(mapper.selectByUser(USER_ID)).thenReturn(List.of(
                row(SubscriptionType.NEW_PROPERTY, null, ContractType.MONTHLY_RENT, null, false)));

        NotificationSubscriptionResponse.NewProperty newProperty = service.get(USER_ID).newProperty();

        assertThat(newProperty.enabled()).isFalse();
        assertThat(newProperty.conditions()).isEqualTo(
                new NotificationSubscriptionResponse.Conditions(List.of(), ContractType.MONTHLY_RENT, null));
    }

    private static NotificationSubscriptionRow row(SubscriptionType type, String district, ContractType contractType,
            Long depositMax, boolean active) {
        return new NotificationSubscriptionRow(type, district, contractType, depositMax, active);
    }
}
