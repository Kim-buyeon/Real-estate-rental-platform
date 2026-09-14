package com.duri.rentalplatform.domain.notification.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@link SseEmitterStore} 의 보관 · 전송 · 제거 · 지표. 웹 계층 없이 전송을 가로채는 연결로 본다. */
class SseEmitterStoreTest {

    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;

    private final SseEmitterStore store = new SseEmitterStore(Duration.ofMinutes(30));

    @Test
    @DisplayName("연결을 만들면 보관하고 타임아웃을 설정값으로 둔다")
    void connectRegistersEmitterWithConfiguredTimeout() {
        SseEmitter emitter = store.connect(USER_ID);

        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(store.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 사용자의 연결 여럿에 모두 보내고, 다른 사용자의 연결에는 보내지 않는다")
    void sendDeliversToEveryEmitterOfTheUserOnly() {
        CapturingSseEmitter first = new CapturingSseEmitter();
        CapturingSseEmitter second = new CapturingSseEmitter();
        CapturingSseEmitter other = new CapturingSseEmitter();
        store.register(USER_ID, first);
        store.register(USER_ID, second);
        store.register(OTHER_USER_ID, other);

        int delivered = store.send(USER_ID, () -> SseEmitter.event().name("RISK_CHANGE").data("x"));

        assertThat(delivered).isEqualTo(2);
        assertThat(first.events()).extracting(CapturingSseEmitter.Event::text)
                .containsExactly("event:RISK_CHANGE\ndata:x\n\n");
        assertThat(second.events()).hasSize(1);
        assertThat(other.events()).isEmpty();
    }

    @Test
    @DisplayName("이 인스턴스에 연결이 없는 사용자에게 보내면 0 을 돌려주고 아무것도 하지 않는다")
    void sendToUserWithoutConnectionReturnsZero() {
        store.register(OTHER_USER_ID, new CapturingSseEmitter());

        assertThat(store.send(USER_ID, () -> SseEmitter.event().data("x"))).isZero();
    }

    @Test
    @DisplayName("전송이 입출력 예외로 실패한 연결은 빼고 나머지 연결에는 보낸다")
    void failedEmitterIsRemovedWhileOthersStillReceive() {
        CapturingSseEmitter broken = new CapturingSseEmitter().failWith(new IOException("끊김"));
        CapturingSseEmitter alive = new CapturingSseEmitter();
        store.register(USER_ID, broken);
        store.register(USER_ID, alive);

        int delivered = store.send(USER_ID, () -> SseEmitter.event().data("x"));

        assertThat(delivered).isEqualTo(1);
        assertThat(alive.events()).hasSize(1);
        assertThat(store.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 완료된 연결로 보내면 빼낸다")
    void completedEmitterIsRemovedOnSend() {
        SseEmitter completed = new SseEmitter();
        store.register(USER_ID, completed);
        completed.complete();

        assertThat(store.send(USER_ID, () -> SseEmitter.event().data("x"))).isZero();
        assertThat(store.connectionCount()).isZero();
    }

    @Test
    @DisplayName("하트비트는 모든 사용자의 연결에 주석을 보내고 끊긴 연결을 뺀다")
    void heartbeatSendsCommentAndDropsBrokenEmitters() {
        CapturingSseEmitter alive = new CapturingSseEmitter();
        CapturingSseEmitter broken = new CapturingSseEmitter().failWith(new IOException("끊김"));
        store.register(USER_ID, alive);
        store.register(OTHER_USER_ID, broken);

        store.sendHeartbeat();

        assertThat(alive.events()).extracting(CapturingSseEmitter.Event::text).containsExactly(":heartbeat\n\n");
        assertThat(store.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("연결 직후 주석 이벤트를 보낸다")
    void openSendsConnectedComment() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();

        SseEmitter opened = store.open(USER_ID, emitter);

        assertThat(opened).isSameAs(emitter);
        assertThat(emitter.events()).extracting(CapturingSseEmitter.Event::text).containsExactly(":connected\n\n");
        assertThat(store.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 연결을 빼면 사용자 항목도 사라져 연결 수가 0 이다")
    void removingLastEmitterClearsUser() {
        SseEmitter emitter = new SseEmitter();
        store.register(USER_ID, emitter);

        store.remove(USER_ID, emitter);
        store.remove(USER_ID, emitter);

        assertThat(store.connectionCount()).isZero();
        assertThat(store.send(USER_ID, () -> SseEmitter.event().data("x"))).isZero();
    }

    @Test
    @DisplayName("게이지 notification.sse.connections 가 현재 연결 수를 읽는다")
    void gaugeReportsCurrentConnectionCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        store.bindTo(registry);
        SseEmitter emitter = new SseEmitter();
        store.register(USER_ID, emitter);
        store.register(OTHER_USER_ID, new SseEmitter());

        assertThat(registry.get(SseEmitterStore.CONNECTIONS_METRIC).gauge().value()).isEqualTo(2.0);

        store.remove(USER_ID, emitter);

        assertThat(registry.get(SseEmitterStore.CONNECTIONS_METRIC).gauge().value()).isEqualTo(1.0);
    }
}
