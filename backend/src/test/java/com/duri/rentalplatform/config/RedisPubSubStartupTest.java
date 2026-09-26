package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.duri.rentalplatform.BackendApplication;
import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationEventResponse;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.sender.SseNotificationSender;
import com.duri.rentalplatform.domain.notification.store.CapturingSseEmitter;
import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis 가 없을 때의 기동과, Redis 가 돌아온 뒤의 구독 회복 — {@link RedisSubscriptionStarter}(NOTI-03, 이슈 #259).
 *
 * <p>2026-09-26 부하 시험에서 Redis 노드가 멈춘 동안 다른 노드의 앱이 구독 컨테이너 시작 실패로 기동하지 못하고 재시작을 반복했다.
 * 공유 컨테이너의 Redis 는 이미 떠 있어 이 상황을 만들 수 없으므로, 두 번째 컨텍스트를 <b>아무것도 듣지 않는 포트</b>에 붙여 띄운다.
 * 그 뒤 같은 포트에 Redis 컨테이너를 고정 바인딩으로 띄워 「Redis 가 돌아온」 상황을 만든다. 무작위 포트로 띄우면 앱이 가리키는
 * 포트와 달라진다.
 *
 * <p>두 테스트는 순서가 있다 — 첫째는 Redis 가 없는 동안, 둘째는 Redis 를 띄운 뒤의 상태를 본다. 컨텍스트를 테스트마다 새로 띄우면
 * 기동 비용이 곱절이 되고, 둘째 테스트가 확인하려는 것이 「없던 Redis 가 나타났을 때」이므로 같은 컨텍스트여야 한다.
 *
 * <p>PostgreSQL 은 공유 컨테이너를 쓴다. 두 번째 컨텍스트를 명령행 인자로 띄우는 이유는 {@code TokenRotationMultiInstanceTest} 와 같다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisPubSubStartupTest {

    private static final int REDIS_EXPOSED_PORT = 6379;
    /** 재시도 간격(5초)에 Redis 기동 · 구독 시간을 더한 여유. */
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final long USER_ID = 9_400_000L;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    private int redisPort;
    private GenericContainer<?> lateRedis;
    private ConfigurableApplicationContext withoutRedis;

    @BeforeAll
    void startInstanceWithoutRedis() throws IOException {
        redisPort = freePort();
        // Redis 가 없어도 여기서 예외가 나지 않아야 한다. 고치기 전에는 run() 이 ApplicationContextException 을 던졌다.
        withoutRedis = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + postgresContainer.getJdbcUrl(),
                        "--spring.datasource.username=" + postgresContainer.getUsername(),
                        "--spring.datasource.password=" + postgresContainer.getPassword(),
                        "--spring.data.redis.host=" + DockerClientFactory.instance().dockerHostIpAddress(),
                        "--spring.data.redis.port=" + redisPort,
                        "--spring.data.redis.password=",
                        "--risk.batch.registry-refresh.enabled=false");
    }

    @AfterAll
    void stopAll() {
        if (withoutRedis != null) {
            withoutRedis.close();
        }
        if (lateRedis != null) {
            lateRedis.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Redis 가 닿지 않아도 컨텍스트가 뜨고, 구독은 맺히지 않은 채로 남는다")
    void contextStartsWithoutRedis() {
        assertThat(withoutRedis.isActive()).isTrue();
        assertThat(withoutRedis.getBean(RedisMessageListenerContainer.class).isListening()).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Redis 가 뜨면 구독이 스스로 맺혀 발행한 알림이 연결에 도착한다")
    void subscriptionRecoversWhenRedisComesUp() {
        lateRedis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(REDIS_EXPOSED_PORT);
        lateRedis.setPortBindings(List.of(redisPort + ":" + REDIS_EXPOSED_PORT));
        lateRedis.start();

        RedisMessageListenerContainer container = withoutRedis.getBean(RedisMessageListenerContainer.class);
        awaitUntil(container::isListening, "구독이 맺히지");

        // 구독 상태 표시만이 아니라 실제로 메시지가 이 인스턴스의 연결까지 오는지 본다.
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        withoutRedis.getBean(SseEmitterStore.class).register(USER_ID, emitter);
        withoutRedis.getBean(SseNotificationSender.class).send(new NotificationDelivery(
                9401L, USER_ID, NotificationType.RISK_CHANGE, 1024L, LocalDateTime.of(2026, 9, 26, 3, 0)));

        awaitUntil(() -> emitter.events().stream()
                .flatMap(event -> event.objects().stream())
                .filter(NotificationEventResponse.class::isInstance)
                .map(NotificationEventResponse.class::cast)
                .anyMatch(body -> body.notificationId() == 9401L), "알림이 연결에 도착하지");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail(what + " 않았다 — " + WAIT + " 대기");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("대기 중 인터럽트");
            }
        }
    }
}
