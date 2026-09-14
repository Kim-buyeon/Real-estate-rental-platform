package com.duri.rentalplatform.domain.notification.sender;

import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;

/**
 * 알림 전달 수단의 계약 — 아키텍처 설계서(알림 전달) 1.1 「전달 수단은 인터페이스로 추상화한다」.
 *
 * <p>알림 이력 저장이 성공 기준이고 전달은 보조 수단이다. 구현은 커밋된 알림만 받으며, 실패해도 이력은 그대로다. 구현이 여럿이면
 * {@link NotificationDispatcher} 가 모두에게 넘긴다 — 서버 전송 이벤트(NOTI-03) · 웹 푸시(NOTI-04)가 붙어도 생성 로직은
 * 바뀌지 않는다.
 */
public interface NotificationSender {

    void send(NotificationDelivery delivery);
}
