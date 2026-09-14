package com.duri.rentalplatform.domain.notification.sender;

import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 발송자 Mock — 실제 전달 없이 기록만 남긴다. 아키텍처 설계서(알림 전달) 1.1 「발송자 인터페이스에 Mock 구현을 두어」.
 *
 * <p>서버 전송 이벤트 구현(NOTI-03)이 붙기 전까지 알림 생성 흐름이 발송 지점까지 도는지 로그로 확인한다.
 */
@Slf4j
@Component
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(NotificationDelivery delivery) {
        log.info("알림 발송(Mock) notificationId={} userId={} type={} propertyId={}",
                delivery.notificationId(), delivery.userId(), delivery.type(), delivery.propertyId());
    }
}
