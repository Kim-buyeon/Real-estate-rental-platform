package com.duri.rentalplatform.domain.notification.listener;

import com.duri.rentalplatform.domain.notification.enums.WishlistChangeType;
import com.duri.rentalplatform.domain.notification.service.NotificationCommandService;
import com.duri.rentalplatform.domain.risk.event.RegistryChangedEvent;
import com.duri.rentalplatform.domain.risk.event.RiskGradeChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 위험도 도메인 이벤트를 관심 매물 모니터링 알림으로 잇는다(NOTI-02).
 *
 * <p><b>커밋 이후</b> — 두 이벤트는 판정 · 이력 교체의 쓰기 트랜잭션 안에서 발행된다. {@code AFTER_COMMIT} 으로 받아 되돌려진
 * 판정의 알림이 생기지 않게 한다 — 아키텍처 설계서(알림 전달) 1.1. 트랜잭션 밖에서 발행된 이벤트는 받지 않는다(기본값).
 *
 * <p><b>비동기</b> — 판정 요청(조회 · 재분석 · 배치)의 응답 시간에 알림 생성이 들지 않게 한다 — 성능 설계 「알림 발송 지연」.
 * 비동기 실행기는 설정(비동기 실행)이 정한다.
 *
 * <p><b>격리</b> — 생성 실패(락 대기 초과 · DB · Redis)는 기록하고 삼킨다. 판정은 이미 커밋되었고, 알림 실패가 판정 결과나
 * 사용자 요청에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class RiskEventListener {

    private final NotificationCommandService notificationCommandService;

    public RiskEventListener(NotificationCommandService notificationCommandService) {
        this.notificationCommandService = notificationCommandService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRiskGradeChanged(RiskGradeChangedEvent event) {
        create(event.propertyId(), WishlistChangeType.RISK_GRADE, event.previousGrade().name(),
                event.riskGrade().name());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistryChanged(RegistryChangedEvent event) {
        create(event.propertyId(), WishlistChangeType.REGISTRY, event.beforeSummary(), event.afterSummary());
    }

    private void create(Long propertyId, WishlistChangeType changeType, String beforeValue, String afterValue) {
        try {
            notificationCommandService.createForWishlist(propertyId, changeType, beforeValue, afterValue);
        } catch (RuntimeException e) {
            log.warn("알림 생성 실패 — 판정은 반영되어 있다. propertyId={} changeType={}", propertyId, changeType, e);
        }
    }
}
