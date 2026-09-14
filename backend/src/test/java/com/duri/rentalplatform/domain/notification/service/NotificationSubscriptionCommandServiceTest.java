package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest.Conditions;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest.NewProperty;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest.Toggle;
import com.duri.rentalplatform.domain.notification.entity.NotificationSubscription;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.notification.repository.NotificationSubscriptionRepository;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.repository.WishlistRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** {@link NotificationSubscriptionCommandService} — 삭제 후 삽입, 저장 값, 검증 400, 모니터링 일괄 반영. */
class NotificationSubscriptionCommandServiceTest {

    private static final long USER_ID = 42L;

    private NotificationSubscriptionRepository subscriptionRepository;
    private WishlistRepository wishlistRepository;
    private NotificationSubscriptionCommandService service;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(NotificationSubscriptionRepository.class);
        wishlistRepository = mock(WishlistRepository.class);
        service = new NotificationSubscriptionCommandService(subscriptionRepository, wishlistRepository);
    }

    @Test
    @DisplayName("사용자 행을 지운 뒤 자치구마다 한 행 + 조건 없는 세 유형을 넣고, 모니터링 값을 관심 매물에 일괄 반영한다")
    void deletesThenInsertsAndAppliesMonitoring() {
        service.replace(USER_ID, request(
                new NewProperty(true, new Conditions(List.of("강서구", "구로구"), ContractType.DEPOSIT_ONLY,
                        250_000_000L)),
                true, false, true));

        InOrder order = inOrder(subscriptionRepository, wishlistRepository);
        order.verify(subscriptionRepository).deleteAllByUserIdInBulk(USER_ID);
        List<NotificationSubscription> saved = captureSaved(order);
        order.verify(wishlistRepository).updateMonitoringByUserId(USER_ID, false);

        assertThat(saved).extracting(NotificationSubscription::getUserId).containsOnly(USER_ID);
        assertThat(saved).extracting(NotificationSubscription::getSubscriptionType,
                        NotificationSubscription::getTargetDistrict, NotificationSubscription::getContractType,
                        NotificationSubscription::getDepositMax, NotificationSubscription::isActive)
                .containsExactly(
                        tuple(SubscriptionType.NEW_PROPERTY, "강서구", ContractType.DEPOSIT_ONLY, 250_000_000L, true),
                        tuple(SubscriptionType.NEW_PROPERTY, "구로구", ContractType.DEPOSIT_ONLY, 250_000_000L, true),
                        tuple(SubscriptionType.RATE_CHANGE, null, null, null, true),
                        tuple(SubscriptionType.WISHLIST_MONITORING, null, null, null, false),
                        tuple(SubscriptionType.CONSULT_SCHEDULE, null, null, null, true));
        assertThat(saved).extracting(NotificationSubscription::getDepositMin).containsOnly(0L);
    }

    @Test
    @DisplayName("켠 신규 매물은 계약 유형 · 상한을 생략할 수 있고 null 로 저장한다")
    void optionalConditions() {
        service.replace(USER_ID, request(new NewProperty(true, new Conditions(List.of("마포구"), null, null)),
                false, true, false));

        InOrder order = inOrder(subscriptionRepository, wishlistRepository);
        order.verify(subscriptionRepository).deleteAllByUserIdInBulk(USER_ID);
        assertThat(captureSaved(order).getFirst()).extracting(NotificationSubscription::getTargetDistrict,
                        NotificationSubscription::getContractType, NotificationSubscription::getDepositMax,
                        NotificationSubscription::isActive)
                .containsExactly("마포구", null, null, true);
        order.verify(wishlistRepository).updateMonitoringByUserId(USER_ID, true);
    }

    @Test
    @DisplayName("끈 신규 매물에 온 조건은 비활성 행으로 보존한다")
    void keepsInactiveConditions() {
        service.replace(USER_ID, request(
                new NewProperty(false, new Conditions(List.of("강서구"), ContractType.SEMI_DEPOSIT, 1L)),
                false, true, false));

        assertThat(captureSaved(inOrder(subscriptionRepository)).getFirst())
                .extracting(NotificationSubscription::getSubscriptionType, NotificationSubscription::getTargetDistrict,
                        NotificationSubscription::getContractType, NotificationSubscription::getDepositMax,
                        NotificationSubscription::isActive)
                .containsExactly(SubscriptionType.NEW_PROPERTY, "강서구", ContractType.SEMI_DEPOSIT, 1L, false);
    }

    @Test
    @DisplayName("끈 신규 매물에 자치구가 없으면 자치구 null 인 비활성 한 행에 나머지 조건을 남긴다")
    void inactiveWithoutDistricts() {
        service.replace(USER_ID, request(
                new NewProperty(false, new Conditions(List.of(), ContractType.MONTHLY_RENT, null)),
                false, true, false));

        assertThat(captureSaved(inOrder(subscriptionRepository)))
                .filteredOn(s -> s.getSubscriptionType() == SubscriptionType.NEW_PROPERTY)
                .extracting(NotificationSubscription::getTargetDistrict, NotificationSubscription::getContractType,
                        NotificationSubscription::isActive)
                .containsExactly(tuple(null, ContractType.MONTHLY_RENT, false));
    }

    @Test
    @DisplayName("켠 신규 매물에 조건이 없으면 400 · field newProperty.conditions 이고 아무것도 바꾸지 않는다")
    void enabledWithoutConditions() {
        assertInvalid(new NewProperty(true, null), NotificationSubscriptionCommandService.CONDITIONS);
    }

    @Test
    @DisplayName("켠 신규 매물에 자치구가 비었으면 400")
    void enabledWithEmptyDistricts() {
        assertInvalid(new NewProperty(true, new Conditions(List.of(), null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
    }

    @Test
    @DisplayName("켠 신규 매물에 자치구 배열이 없으면(null) 400")
    void enabledWithNullDistricts() {
        assertInvalid(new NewProperty(true, new Conditions(null, null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
    }

    @Test
    @DisplayName("서울 자치구명이 아니면 400 — 끈 설정도 같다")
    void unknownDistrict() {
        assertInvalid(new NewProperty(true, new Conditions(List.of("강서구", "분당구"), null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
        assertInvalid(new NewProperty(false, new Conditions(List.of("Gangseo-gu"), null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
    }

    @Test
    @DisplayName("자치구 이름에 null 이 섞이면 400")
    void nullDistrictName() {
        assertInvalid(new NewProperty(true, new Conditions(Arrays.asList("강서구", null), null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
    }

    @Test
    @DisplayName("중복 자치구는 400 — 25개 상한도 이 규칙으로 막힌다")
    void duplicatedDistrict() {
        assertInvalid(new NewProperty(true, new Conditions(List.of("강서구", "구로구", "강서구"), null, null)),
                NotificationSubscriptionCommandService.DISTRICTS);
    }

    private void assertInvalid(NewProperty newProperty, String field) {
        assertThatThrownBy(() -> service.replace(USER_ID, request(newProperty, true, true, true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.getField()).isEqualTo(field);
                });
        verifyNoInteractions(subscriptionRepository, wishlistRepository);
    }

    @SuppressWarnings("unchecked")
    private List<NotificationSubscription> captureSaved(InOrder order) {
        ArgumentCaptor<Iterable<NotificationSubscription>> captor = ArgumentCaptor.forClass(Iterable.class);
        order.verify(subscriptionRepository).saveAll(captor.capture());
        return (List<NotificationSubscription>) captor.getValue();
    }

    private static NotificationSubscriptionUpdateRequest request(NewProperty newProperty, boolean rateChange,
            boolean wishlistMonitoring, boolean consultSchedule) {
        return new NotificationSubscriptionUpdateRequest(newProperty, new Toggle(rateChange),
                new Toggle(wishlistMonitoring), new Toggle(consultSchedule));
    }
}
