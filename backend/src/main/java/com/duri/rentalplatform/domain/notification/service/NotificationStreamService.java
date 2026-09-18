package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.domain.notification.dto.response.NotificationEventResponse;
import com.duri.rentalplatform.domain.notification.dto.response.StreamTicketResponse;
import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
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
 * <p><b>티켓</b> — 연결을 여는 일회용 자격 증명이다. 발급은 액세스 토큰으로 인증된 요청만 할 수 있고, 티켓 자체는 수신 연결 하나를
 * 여는 데만 쓰인다. 저장과 소비는 {@link StreamTicketStore} 가 한다.
 *
 * <p><b>연결 수명</b> — 인증에 쓴 액세스 토큰의 남은 유효 시간이며, 설정 상한({@code notification.sse.timeout})보다 길지 않다.
 * 티켓으로 연결해도 같다 — 티켓이 발급 때의 토큰 만료를 담고 있어 두 경로의 수명이 같은 값으로 계산된다. 티켓 자체의 수명은
 * 연결 수명과 무관하다(연결을 여는 데만 쓰인다).
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
    private final StreamTicketStore streamTicketStore;
    private final Duration maxTimeout;
    private final Clock clock;

    @Autowired
    public NotificationStreamService(SseEmitterStore sseEmitterStore, StreamTicketStore streamTicketStore,
            @Value("${notification.sse.timeout}") Duration maxTimeout) {
        this(sseEmitterStore, streamTicketStore, maxTimeout, Clock.systemUTC());
    }

    /** 티켓 발급을 쓰지 않는 테스트는 {@code streamTicketStore} 에 null 을 넘긴다. */
    NotificationStreamService(SseEmitterStore sseEmitterStore, StreamTicketStore streamTicketStore,
            Duration maxTimeout, Clock clock) {
        this.sseEmitterStore = sseEmitterStore;
        this.streamTicketStore = streamTicketStore;
        this.maxTimeout = maxTimeout;
        this.clock = clock;
    }

    /**
     * 연결을 열 일회용 티켓을 발급한다. 호출자는 이미 액세스 토큰으로 인증된 사용자다.
     *
     * <p>그 토큰의 만료 시각을 티켓에 함께 담는다 — 티켓으로 연 연결의 수명이 헤더 토큰으로 연 연결과 같아야 한다.
     *
     * <p>트랜잭션을 열지 않는다 — Redis 에만 쓴다.
     *
     * @param tokenExpiresAt 이 요청을 인증한 액세스 토큰의 만료 시각. 알 수 없으면 null
     */
    public StreamTicketResponse issueTicket(Long userId, Instant tokenExpiresAt) {
        return new StreamTicketResponse(streamTicketStore.issue(userId, tokenExpiresAt));
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
