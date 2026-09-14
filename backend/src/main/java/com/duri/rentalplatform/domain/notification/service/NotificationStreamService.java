package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.domain.notification.dto.response.NotificationEventResponse;
import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 알림 수신(NOTI-03) — 연결 열기와 이 인스턴스 연결로의 전달. API 명세서(알림) 1.1 · 1.4.
 *
 * <p><b>연결 수명</b> — 인증에 쓴 액세스 토큰의 남은 유효 시간이다. 설정 상한({@code notification.sse.timeout})보다 길지 않다. 만료
 * 직전 토큰으로 연결해도 만료 뒤까지 연결이 유지되지 않고, 클라이언트는 새 토큰으로 재연결한다. 이미 만료된 토큰은 인증 필터가 막는다.
 *
 * <p><b>전달</b> — 이벤트 이름은 알림 유형, 본문은 명세 1.4 형식으로 바꿔 보관소에 넘긴다. 연결이 없으면 아무것도 하지 않는다 —
 * 다른 인스턴스가 보낸다(아키텍처 설계서(알림 전달) 1.1).
 *
 * <p>트랜잭션을 열지 않는다 — 데이터베이스를 쓰지 않는다.
 */
@Service
public class NotificationStreamService {

    /** 수명 하한. 만료가 코앞이어도 연결 직후 주석은 나가게 한다. */
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    private final SseEmitterStore sseEmitterStore;
    private final Duration maxTimeout;
    private final Clock clock;

    @Autowired
    public NotificationStreamService(SseEmitterStore sseEmitterStore,
            @Value("${notification.sse.timeout}") Duration maxTimeout) {
        this(sseEmitterStore, maxTimeout, Clock.systemUTC());
    }

    NotificationStreamService(SseEmitterStore sseEmitterStore, Duration maxTimeout, Clock clock) {
        this.sseEmitterStore = sseEmitterStore;
        this.maxTimeout = maxTimeout;
        this.clock = clock;
    }

    /**
     * 사용자의 연결을 연다.
     *
     * @param tokenExpiresAt 인증에 쓴 액세스 토큰의 만료 시각. 알 수 없으면 null — 설정 상한을 쓴다
     */
    public SseEmitter connect(Long userId, Instant tokenExpiresAt) {
        return sseEmitterStore.connect(userId, timeoutFor(tokenExpiresAt));
    }

    /** 이 인스턴스가 가진 받을 사용자의 연결로 이벤트를 보낸다. @return 전송에 성공한 연결 수 */
    public int deliver(NotificationDelivery delivery) {
        NotificationEventResponse body = NotificationEventResponse.from(delivery);
        return sseEmitterStore.send(delivery.userId(), () -> SseEmitter.event()
                .name(delivery.type().name())
                .data(body, MediaType.APPLICATION_JSON));
    }

    Duration timeoutFor(Instant tokenExpiresAt) {
        if (tokenExpiresAt == null) {
            return maxTimeout;
        }
        Duration remaining = Duration.between(clock.instant(), tokenExpiresAt);
        if (remaining.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        return remaining.compareTo(maxTimeout) > 0 ? maxTimeout : remaining;
    }
}
