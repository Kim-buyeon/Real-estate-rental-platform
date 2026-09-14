package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.common.lock.DistributedLock;
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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관심 매물 모니터링 알림 생성(NOTI-02). 위험 등급 변경 · 등기 변동이 커밋된 뒤 리스너가 부른다.
 *
 * <p><b>대상</b> — 매물의 관심 매물 중 {@code monitoring_yn} 이 켜진 것(비즈니스 로직 정의서 7장). 수신 설정(NOTI-01)은 이 값에
 * 이미 반영되어 있어 구독 테이블을 다시 읽지 않는다.
 *
 * <p><b>트랜잭션 — {@code REQUIRES_NEW}</b> — 커밋 뒤 단계에서 불리므로 새 경계가 필요하다. 기본 전파는 커밋이 끝난 원래
 * 트랜잭션의 자원에 합류해 저장이 반영되지 않는다. 사용자 수만큼의 공통 · 하위 행이 한 경계다.
 *
 * <p><b>중복 방지</b> — 사용자마다 {@link NotificationDedupStore} 키를 먼저 걸고, 이미 있으면 그 사용자를 건너뛴다. 저장이
 * 롤백되면 이번에 건 키를 푼다. 같은 매물의 생성은 분산 락({@code noti:create:{propertyId}})으로 인스턴스를 넘어 하나씩 돈다 —
 * 아키텍처 설계서(횡단 관심사) 1.1 알림 생성. 락은 트랜잭션보다 바깥이라 커밋까지 쥔다.
 *
 * <p><b>발송</b> — 커밋이 끝난 뒤 {@link NotificationDispatcher} 에 넘긴다. 롤백된 알림은 나가지 않고, 전달은 비동기라 이
 * 경계를 붙잡지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCommandService {

    private final WishlistRepository wishlistRepository;
    private final NotificationRepository notificationRepository;
    private final WishlistNotificationRepository wishlistNotificationRepository;
    private final NotificationDedupStore dedupStore;
    private final NotificationDispatcher dispatcher;

    /**
     * 매물의 모니터링 대상 사용자마다 알림을 만든다.
     *
     * @param propertyId  변동이 난 매물
     * @param changeType  변동 유형 — 알림 유형도 여기서 정해진다
     * @param beforeValue 변동 전 값
     * @param afterValue  변동 후 값. 중복 방지 키에 들어간다
     * @return 새로 만든 알림 수. 대상이 없거나 모두 중복이면 0
     */
    @DistributedLock(
            key = "'noti:create:' + #propertyId",
            waitTimeout = "${notification.create.lock-wait-timeout}",
            pollInterval = "${notification.create.lock-poll-interval}",
            leaseTime = "${notification.create.lock-lease-time}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int createForWishlist(Long propertyId, WishlistChangeType changeType, String beforeValue,
            String afterValue) {
        NotificationType type = changeType.getNotificationType();
        List<Long> claimedUsers = new ArrayList<>();
        List<NotificationDelivery> deliveries = new ArrayList<>();
        // 저장 도중 예외가 나도 이미 건 키를 풀 수 있게 동기화부터 건다.
        TransactionSynchronizationManager.registerSynchronization(
                afterTransaction(propertyId, type, afterValue, claimedUsers, deliveries));

        for (Wishlist wishlist : wishlistRepository.findByPropertyIdAndMonitoringTrue(propertyId)) {
            Long userId = wishlist.getUserId();
            if (!dedupStore.claim(userId, type, propertyId, afterValue)) {
                log.debug("중복 알림 건너뜀 userId={} type={} propertyId={}", userId, type, propertyId);
                continue;
            }
            claimedUsers.add(userId);

            Notification notification = notificationRepository.save(Notification.unread(userId, type));
            wishlistNotificationRepository.save(WishlistNotification.of(notification.getNotifId(), propertyId,
                    wishlist.getWishId(), changeType, beforeValue, afterValue));
            deliveries.add(new NotificationDelivery(notification.getNotifId(), userId, type, propertyId,
                    notification.getCreatedAt()));
        }
        return deliveries.size();
    }

    /** 커밋되면 발송하고, 롤백되면 건 키를 푼다. */
    private TransactionSynchronization afterTransaction(Long propertyId, NotificationType type, String afterValue,
            List<Long> claimedUsers, List<NotificationDelivery> deliveries) {
        return new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!deliveries.isEmpty()) {
                    dispatcher.dispatch(List.copyOf(deliveries));
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                for (Long userId : claimedUsers) {
                    try {
                        dedupStore.release(userId, type, propertyId, afterValue);
                    } catch (RuntimeException e) {
                        log.warn("중복 방지 키 해제 실패 — 만료로 풀린다. userId={} type={} propertyId={}",
                                userId, type, propertyId, e);
                    }
                }
            }
        };
    }
}
