package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.notification.entity.Notification;
import com.duri.rentalplatform.domain.notification.entity.WishlistNotification;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.enums.WishlistChangeType;
import com.duri.rentalplatform.domain.notification.repository.NotificationRepository;
import com.duri.rentalplatform.domain.notification.repository.WishlistNotificationRepository;
import com.duri.rentalplatform.domain.notification.sender.NotificationDispatcher;
import com.duri.rentalplatform.domain.notification.store.NotificationDedupStore;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import com.duri.rentalplatform.domain.property.entity.Wishlist;
import com.duri.rentalplatform.domain.property.repository.WishlistRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link NotificationCommandService} — 대상 선정 · 중복 키 건너뜀 · 저장 값 · 커밋 후 발송 · 롤백 시 키 해제.
 *
 * <p>트랜잭션은 띄우지 않는다. 동기화만 켜 두고, 서비스가 건 동기화의 커밋 · 롤백 콜백을 테스트가 직접 부른다 — 「커밋 전에는
 * 발송하지 않는다」를 호출 순서로 확인한다. 실제 커밋 · 롤백 경로는 통합 테스트가 본다.
 */
class NotificationCommandServiceTest {

    private static final long PROPERTY_ID = 1024L;
    private static final long USER_A = 1L;
    private static final long USER_B = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 3, 5);

    private WishlistRepository wishlistRepository;
    private NotificationRepository notificationRepository;
    private WishlistNotificationRepository wishlistNotificationRepository;
    private NotificationDedupStore dedupStore;
    private NotificationDispatcher dispatcher;
    private NotificationCommandService service;

    @BeforeEach
    void setUp() {
        wishlistRepository = mock(WishlistRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        wishlistNotificationRepository = mock(WishlistNotificationRepository.class);
        dedupStore = mock(NotificationDedupStore.class);
        dispatcher = mock(NotificationDispatcher.class);
        service = new NotificationCommandService(wishlistRepository, notificationRepository,
                wishlistNotificationRepository, dedupStore, dispatcher);

        AtomicLong ids = new AtomicLong(9000);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification notification = inv.getArgument(0);
            ReflectionTestUtils.setField(notification, "notifId", ids.incrementAndGet());
            ReflectionTestUtils.setField(notification, "createdAt", NOW);
            return notification;
        });
        when(dedupStore.claim(anyLong(), any(), anyLong(), anyString())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("모니터링이 켜진 관심 매물의 사용자마다 공통 행과 관심 매물 알림 행을 하나씩 저장한다")
    void createsPerMonitoringWishlist() {
        givenWishlists(wishlist(11L, USER_A), wishlist(12L, USER_B));

        int created = service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Notification> notifications = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notifications.capture());
        assertThat(notifications.getAllValues())
                .extracting(Notification::getUserId, Notification::getType, Notification::isRead)
                .containsExactly(
                        tuple(USER_A, NotificationType.RISK_CHANGE, false),
                        tuple(USER_B, NotificationType.RISK_CHANGE, false));

        ArgumentCaptor<WishlistNotification> details = ArgumentCaptor.forClass(WishlistNotification.class);
        verify(wishlistNotificationRepository, times(2)).save(details.capture());
        assertThat(details.getAllValues())
                .extracting(WishlistNotification::getNotifId, WishlistNotification::getPropertyId,
                        WishlistNotification::getWishId, WishlistNotification::getChangeType,
                        WishlistNotification::getBeforeValue, WishlistNotification::getAfterValue)
                .containsExactly(
                        tuple(9001L, PROPERTY_ID, 11L, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER"),
                        tuple(9002L, PROPERTY_ID, 12L, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER"));
    }

    @Test
    @DisplayName("대상은 매물의 모니터링 켜진 관심 매물로만 고른다 — 없으면 아무것도 저장하지 않는다")
    void noTargets() {
        givenWishlists();

        int created = service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "SAFE", "CAUTION");

        assertThat(created).isZero();
        verify(wishlistRepository).findByPropertyIdAndMonitoringTrue(PROPERTY_ID);
        verify(notificationRepository, never()).save(any());
        verify(wishlistNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("중복 방지 키가 이미 있는 사용자는 건너뛰고 나머지만 만든다")
    void skipsDuplicatedUser() {
        givenWishlists(wishlist(11L, USER_A), wishlist(12L, USER_B));
        when(dedupStore.claim(USER_A, NotificationType.RISK_CHANGE, PROPERTY_ID, "DANGER")).thenReturn(false);

        int created = service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<Notification> notifications = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notifications.capture());
        assertThat(notifications.getValue().getUserId()).isEqualTo(USER_B);
    }

    @Test
    @DisplayName("중복 방지 키는 사용자 · 알림 유형 · 매물 · 변동 후 값으로 건다")
    void claimsWithAfterValue() {
        givenWishlists(wishlist(11L, USER_A));

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.REGISTRY, "갑구 2 · 을구 1", "갑구 2 · 을구 0");

        verify(dedupStore).claim(USER_A, NotificationType.REGISTRY_CHANGE, PROPERTY_ID, "갑구 2 · 을구 0");
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notification.capture());
        assertThat(notification.getValue().getType()).isEqualTo(NotificationType.REGISTRY_CHANGE);
    }

    @Test
    @DisplayName("발송은 커밋 뒤에만 한다 — 저장 중에는 부르지 않고, 커밋되면 만든 알림 전부를 한 번에 넘긴다")
    void dispatchesOnlyAfterCommit() {
        givenWishlists(wishlist(11L, USER_A), wishlist(12L, USER_B));

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");
        verify(dispatcher, never()).dispatch(anyList());

        synchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(dispatcher).dispatch(List.of(
                new NotificationDelivery(9001L, USER_A, NotificationType.RISK_CHANGE, PROPERTY_ID, NOW),
                new NotificationDelivery(9002L, USER_B, NotificationType.RISK_CHANGE, PROPERTY_ID, NOW)));
    }

    @Test
    @DisplayName("만든 알림이 없으면 커밋 뒤에도 발송하지 않는다")
    void noDispatchWhenNothingCreated() {
        givenWishlists(wishlist(11L, USER_A));
        when(dedupStore.claim(anyLong(), any(), anyLong(), anyString())).thenReturn(false);

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");
        commit();

        verify(dispatcher, never()).dispatch(anyList());
    }

    @Test
    @DisplayName("저장이 실패해 롤백되면 이번에 건 키를 모두 풀고 발송하지 않는다")
    void releasesClaimedKeysOnRollback() {
        givenWishlists(wishlist(11L, USER_A), wishlist(12L, USER_B));
        when(wishlistNotificationRepository.save(any(WishlistNotification.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION",
                "DANGER")).isInstanceOf(DataIntegrityViolationException.class);
        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(dedupStore).release(USER_A, NotificationType.RISK_CHANGE, PROPERTY_ID, "DANGER");
        verify(dedupStore).release(USER_B, NotificationType.RISK_CHANGE, PROPERTY_ID, "DANGER");
        verify(dispatcher, never()).dispatch(anyList());
    }

    @Test
    @DisplayName("건너뛴 사용자의 키는 이번 호출이 건 것이 아니라 롤백돼도 풀지 않는다")
    void doesNotReleaseSkippedKeyOnRollback() {
        givenWishlists(wishlist(11L, USER_A), wishlist(12L, USER_B));
        when(dedupStore.claim(USER_A, NotificationType.RISK_CHANGE, PROPERTY_ID, "DANGER")).thenReturn(false);

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");
        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(dedupStore, never()).release(eq(USER_A), any(), anyLong(), anyString());
        verify(dedupStore).release(USER_B, NotificationType.RISK_CHANGE, PROPERTY_ID, "DANGER");
    }

    @Test
    @DisplayName("커밋되면 키를 풀지 않는다")
    void keepsKeysOnCommit() {
        givenWishlists(wishlist(11L, USER_A));

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");
        commit();

        verify(dedupStore, never()).release(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("롤백 뒤 키 해제가 실패해도 예외를 올리지 않는다 — 키는 만료로 풀린다")
    void releaseFailureIsSwallowed() {
        givenWishlists(wishlist(11L, USER_A));
        doThrow(new IllegalStateException("redis down"))
                .when(dedupStore).release(anyLong(), any(), anyLong(), anyString());

        service.createForWishlist(PROPERTY_ID, WishlistChangeType.RISK_GRADE, "CAUTION", "DANGER");

        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations = synchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private static List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }

    private void givenWishlists(Wishlist... wishlists) {
        when(wishlistRepository.findByPropertyIdAndMonitoringTrue(PROPERTY_ID)).thenReturn(List.of(wishlists));
    }

    private static Wishlist wishlist(long wishId, long userId) {
        Wishlist wishlist = Wishlist.register(userId, PROPERTY_ID, true);
        ReflectionTestUtils.setField(wishlist, "wishId", wishId);
        return wishlist;
    }
}
