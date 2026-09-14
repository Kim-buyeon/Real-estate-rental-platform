package com.duri.rentalplatform.domain.notification.scheduler;

import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실시간 수신 연결의 하트비트(NOTI-03). 간격은 설정 {@code notification.sse.heartbeat-interval}.
 *
 * <p>이벤트가 없는 동안 프록시 · 브라우저가 연결을 유휴로 끊지 않게 주석을 흘리고, 클라이언트가 이미 끊은 연결을 전송 실패로 드러내
 * 보관소에서 뺀다.
 *
 * <p><b>분산 락을 걸지 않는다</b> — 다른 반복 실행과 달리 한 번만 실행하는 일이 아니다. 각 인스턴스가 자기 연결에 보내야 하므로 두
 * 인스턴스 모두 돈다.
 */
@Component
public class SseHeartbeatScheduler {

    private final SseEmitterStore sseEmitterStore;

    public SseHeartbeatScheduler(SseEmitterStore sseEmitterStore) {
        this.sseEmitterStore = sseEmitterStore;
    }

    @Scheduled(fixedRateString = "${notification.sse.heartbeat-interval}")
    public void sendHeartbeat() {
        sseEmitterStore.sendHeartbeat();
    }
}
