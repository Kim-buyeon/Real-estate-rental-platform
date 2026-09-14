package com.duri.rentalplatform.domain.notification.store;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 이 인스턴스가 보유한 실시간 수신 연결 — 사용자별 {@link SseEmitter} 목록(NOTI-03).
 *
 * <p><b>인스턴스 메모리에 둔다</b> — 연결은 이 프로세스의 소켓이라 공유 저장소에 옮길 수 없다. 두 인스턴스 사이의 전달은 연결 위치를
 * 저장하지 않고 발행 · 구독으로 전 인스턴스에 알린 뒤 각자 여기서 보유 여부를 확인한다 — 아키텍처 설계서(알림 전달) 1.1. 공유 상태가
 * 아니라 인스턴스 지역 자원이므로 무상태 전제와 충돌하지 않는다.
 *
 * <p><b>한 사용자 여러 연결</b> — 명세 1.1 은 앱 전역에 하나를 요구하지만 탭을 여러 개 열면 연결도 여럿이다. 목록으로 두고 모두에게
 * 보낸다.
 *
 * <p><b>제거</b> — 완료 · 타임아웃 · 오류 콜백과 전송 실패에서 뺀다. 연결은 응답이 끝나지 않아 누수되어도 오류가 나지 않으므로 활성
 * 연결 수를 지표 {@value #CONNECTIONS_METRIC} 로 노출한다 — 같은 설계서 1.1. 지표는 {@link MeterBinder} 로 두어 레지스트리 빈이
 * 있을 때 묶인다.
 */
@Slf4j
@Component
public class SseEmitterStore implements MeterBinder {

    public static final String CONNECTIONS_METRIC = "notification.sse.connections";

    private static final String CONNECTED_COMMENT = "connected";
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    private final ConcurrentMap<Long, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final Duration timeout;

    public SseEmitterStore(@Value("${notification.sse.timeout}") Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 사용자의 새 연결을 만들어 보관하고, 연결 직후 주석 이벤트를 보낸다. 주석은 클라이언트에 이벤트로 전달되지 않지만 응답 헤더와 첫
     * 바이트를 바로 흘려 프록시 · 브라우저가 연결이 성립했음을 알게 한다.
     */
    public SseEmitter connect(Long userId) {
        return open(userId, new SseEmitter(timeout.toMillis()));
    }

    SseEmitter open(Long userId, SseEmitter emitter) {
        register(userId, emitter);
        sendOrRemove(userId, emitter, SseEmitter.event().comment(CONNECTED_COMMENT));
        return emitter;
    }

    /**
     * 만들어진 연결을 보관하고 수명 콜백을 건다. {@link #connect} 가 부르며, 다중 인스턴스 테스트가 전송을 가로채는 연결을 넣을 때도
     * 쓴다.
     *
     * <p>타임아웃에서 직접 완료한다 — 완료하지 않으면 MVC 가 타임아웃 예외로 이미 커밋된 스트림에 오류 응답을 쓰려 한다.
     */
    public void register(Long userId, SseEmitter emitter) {
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(userId, emitter));
        // 추가와 빈 목록 제거가 같은 키에서 겹쳐도 목록을 잃지 않도록 키 단위 원자 연산으로만 바꾼다.
        emittersByUser.compute(userId, (id, emitters) -> {
            List<SseEmitter> target = emitters == null ? new CopyOnWriteArrayList<>() : emitters;
            target.add(emitter);
            return target;
        });
    }

    /**
     * 사용자가 이 인스턴스에 가진 모든 연결로 이벤트를 보낸다. 연결마다 새 이벤트를 만든다 — 이벤트 빌더는 한 번 쓰는 값이다.
     *
     * @return 전송에 성공한 연결 수. 이 인스턴스에 연결이 없으면 0
     */
    public int send(Long userId, Supplier<SseEventBuilder> event) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return 0;
        }
        int delivered = 0;
        for (SseEmitter emitter : emitters) {
            if (sendOrRemove(userId, emitter, event.get())) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 보유한 모든 연결에 하트비트 주석을 보낸다. 끊긴 연결은 여기서 드러나 제거된다. */
    public void sendHeartbeat() {
        emittersByUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                sendOrRemove(userId, emitter, SseEmitter.event().comment(HEARTBEAT_COMMENT));
            }
        });
    }

    /** 이 인스턴스의 활성 연결 수. */
    public int connectionCount() {
        return emittersByUser.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(CONNECTIONS_METRIC, this, store -> store.connectionCount())
                .description("이 인스턴스가 보유한 실시간 알림 수신(SSE) 연결 수")
                .register(registry);
    }

    void remove(Long userId, SseEmitter emitter) {
        emittersByUser.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /**
     * 보내고, 실패하면 연결을 뺀다. 입출력 실패는 클라이언트가 끊은 것이고 컨테이너가 오류 콜백으로 응답을 마무리하므로 여기서 완료를
     * 부르지 않는다. 이미 완료된 연결로 보내면 {@link IllegalStateException} 이다.
     */
    private boolean sendOrRemove(Long userId, SseEmitter emitter, SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            remove(userId, emitter);
            log.info("SSE 전송 실패 — 연결을 제거한다. userId={} reason={}", userId, e.toString());
            return false;
        }
    }
}
