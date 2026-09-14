package com.duri.rentalplatform.domain.notification.sender;

import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 커밋된 알림을 모든 발송자에게 넘긴다. 아키텍처 설계서(알림 전달) 1.1 「알림 생성과 발송을 분리한다」.
 *
 * <p><b>비동기</b> — 호출한 스레드(알림 생성)를 전달 시간만큼 붙잡지 않는다. 개발 환경 설계 「발송은 트랜잭션 커밋 이후
 * 비동기로」. 비동기 실행은 프록시가 걸므로 이 메서드는 별도 빈의 public 메서드로 둔다.
 *
 * <p><b>격리</b> — 한 알림 · 한 발송자의 실패를 기록만 하고 다음으로 넘어간다. 한 사용자의 연결 문제가 다른 사용자의 전달을
 * 막지 않고, 한 수단의 장애가 다른 수단을 막지 않는다. 이력은 이미 커밋되어 목록 조회로 확인할 수 있다.
 */
@Slf4j
@Component
public class NotificationDispatcher {

    private final List<NotificationSender> senders;

    public NotificationDispatcher(List<NotificationSender> senders) {
        this.senders = senders;
    }

    @Async
    public void dispatch(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            for (NotificationSender sender : senders) {
                try {
                    sender.send(delivery);
                } catch (RuntimeException e) {
                    log.warn("알림 발송 실패 — 이력은 남아 있다. sender={} notificationId={}",
                            sender.getClass().getSimpleName(), delivery.notificationId(), e);
                }
            }
        }
    }
}
