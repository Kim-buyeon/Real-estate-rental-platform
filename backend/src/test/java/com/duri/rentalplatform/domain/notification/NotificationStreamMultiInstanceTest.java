package com.duri.rentalplatform.domain.notification;

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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * NOTI-03 실시간 수신 이벤트가 인스턴스를 가로질러 전달되는지에 대한 다중 인스턴스 검증 — testing.md 1.1 「다중 인스턴스 테스트」의
 * SSE 이벤트 팬아웃.
 *
 * <p>연결은 사용자가 붙은 인스턴스의 메모리에만 있다. 발송자가 자기 인스턴스의 연결에만 쓰면 알림을 만든 인스턴스와 연결된 인스턴스가
 * 다를 때 알림이 닿지 않고, 단일 인스턴스 테스트는 그래도 전부 통과한다. 여기서는 컨텍스트 둘이 같은 Redis 를 보게 하고 한쪽의
 * 발송자로 보낸 알림이 다른 쪽 연결에 도착하는지 본다. 컨텍스트 둘 · 같은 컨테이너 구성과 그 이유는
 * {@code TokenRotationMultiInstanceTest} 와 같다.
 *
 * <p><b>연결</b> — 인스턴스 B 는 웹 서버가 없으므로 HTTP 로 연결을 열 수 없다. 확인하려는 것은 연결 수립이 아니라 「발행한 인스턴스가
 * 아닌 곳의 연결에 도착하는가」이므로, 각 인스턴스의 보관소에 전송을 가로채는 연결을 직접 넣는다. 발송도 비동기 분배자를 거치지 않고
 * 발송자 빈을 직접 부른다 — 분배는 NOTI-02 가 검증했다.
 *
 * <p><b>중복 없음</b> — 전달은 비동기라 「한 번만 왔다」를 기다림으로 확정할 수 없다. 대상 이벤트가 온 뒤 같은 사용자에게 표지 이벤트를
 * 하나 더 보내고, 표지가 온 시점에 대상 이벤트가 정확히 한 번인지 본다. 같은 구독 연결의 메시지는 순서대로 처리된다.
 *
 * <p>데이터베이스를 쓰지 않는다 — 발송 값은 식별자뿐이고 구독자는 조회하지 않는다. 사용자 식별자는 다른 테스트의 알림과 겹치지 않게
 * 테스트마다 새 값을 쓴다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStreamMultiInstanceTest {

    private static final int REDIS_EXPOSED_PORT = 6379;
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final AtomicLong USER_IDS = new AtomicLong(9_300_000L);
    private static final long MARKER_NOTIFICATION_ID = -1L;

    @Autowired
    SseEmitterStore instanceAStore;

    @Autowired
    SseNotificationSender instanceASender;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    private ConfigurableApplicationContext instanceBContext;
    private SseEmitterStore instanceBStore;
    private SseNotificationSender instanceBSender;

    @BeforeAll
    void startSecondInstance() {
        instanceBContext = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .run(sharedInfrastructureArguments());
        instanceBStore = instanceBContext.getBean(SseEmitterStore.class);
        instanceBSender = instanceBContext.getBean(SseNotificationSender.class);
        awaitBothSubscribed();
    }

    @AfterAll
    void stopSecondInstance() {
        if (instanceBContext != null) {
            instanceBContext.close();
        }
    }

    /** 명령행 인자로 넘기는 이유와 Redis 비밀번호를 비우는 이유는 {@code TokenRotationMultiInstanceTest} 와 같다. */
    private String[] sharedInfrastructureArguments() {
        return new String[] {
                "--spring.datasource.url=" + postgresContainer.getJdbcUrl(),
                "--spring.datasource.username=" + postgresContainer.getUsername(),
                "--spring.datasource.password=" + postgresContainer.getPassword(),
                "--spring.data.redis.host=" + redisContainer.getHost(),
                "--spring.data.redis.port=" + redisContainer.getMappedPort(REDIS_EXPOSED_PORT),
                "--spring.data.redis.password=",
                "--risk.batch.registry-refresh.enabled=false"
        };
    }

    /**
     * 두 인스턴스의 채널 구독이 성립할 때까지 기다린다. 구독은 컨텍스트 시작과 함께 비동기로 성립하므로, 기다리지 않으면 첫 테스트의
     * 발행이 구독 전에 지나가 팬아웃 결함과 구분되지 않는 실패가 난다. 도착할 때까지 표지를 거듭 발행한다 — 이 사용자는 테스트에 쓰지
     * 않는다.
     */
    private void awaitBothSubscribed() {
        long probeUserId = USER_IDS.getAndIncrement();
        CapturingSseEmitter onA = new CapturingSseEmitter();
        CapturingSseEmitter onB = new CapturingSseEmitter();
        instanceAStore.register(probeUserId, onA);
        instanceBStore.register(probeUserId, onB);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (onA.events().isEmpty() || onB.events().isEmpty()) {
            if (System.nanoTime() - deadline > 0) {
                fail("두 인스턴스의 알림 채널 구독이 " + WAIT + " 안에 성립하지 않았다");
            }
            instanceASender.send(delivery(MARKER_NOTIFICATION_ID, probeUserId));
            sleep();
        }
    }

    @Test
    @DisplayName("인스턴스 A 와 B 는 서로 다른 보관소 · 발송자 빈이다")
    void instancesHaveDistinctStoresAndSenders() {
        // 나머지 전부의 전제다. 같은 보관소면 아래 테스트는 한 인스턴스 안의 전달을 확인하면서 초록으로 남는다.
        assertThat(instanceBStore).isNotSameAs(instanceAStore);
        assertThat(instanceBSender).isNotSameAs(instanceASender);
    }

    @Test
    @DisplayName("인스턴스 B 에만 연결된 사용자에게 인스턴스 A 가 보낸 알림이 B 의 연결로 한 번 도착한다")
    void deliveryFromInstanceAReachesConnectionOnInstanceB() {
        long userId = USER_IDS.getAndIncrement();
        CapturingSseEmitter onB = new CapturingSseEmitter();
        instanceBStore.register(userId, onB);

        instanceASender.send(delivery(9012L, userId));

        assertReceivedExactlyOnce(onB, userId, 9012L);
        // 하트비트 주석이 끼어들 수 있어 순서가 아니라 본문을 가진 이벤트의 이름을 본다.
        assertThat(onB.events())
                .filteredOn(event -> !event.objects().isEmpty())
                .extracting(CapturingSseEmitter.Event::text)
                .allMatch(text -> text.startsWith("event:RISK_CHANGE\n"));
    }

    @Test
    @DisplayName("인스턴스 A 에만 연결된 사용자에게 인스턴스 B 가 보낸 알림이 A 의 연결로 한 번 도착한다")
    void deliveryFromInstanceBReachesConnectionOnInstanceA() {
        // 방향을 바꿔도 같아야 한다. 한쪽만 확인하면 B 만 구독하는 구성도 통과한다.
        long userId = USER_IDS.getAndIncrement();
        CapturingSseEmitter onA = new CapturingSseEmitter();
        instanceAStore.register(userId, onA);

        instanceBSender.send(delivery(9013L, userId));

        assertReceivedExactlyOnce(onA, userId, 9013L);
    }

    @Test
    @DisplayName("같은 사용자가 두 인스턴스에 연결을 하나씩 가지면 두 연결 모두 한 번씩 받는다")
    void deliveryReachesConnectionsOnBothInstances() {
        long userId = USER_IDS.getAndIncrement();
        CapturingSseEmitter onA = new CapturingSseEmitter();
        CapturingSseEmitter onB = new CapturingSseEmitter();
        instanceAStore.register(userId, onA);
        instanceBStore.register(userId, onB);

        instanceASender.send(delivery(9014L, userId));

        assertReceivedExactlyOnce(onA, userId, 9014L);
        assertReceivedExactlyOnce(onB, userId, 9014L);
    }

    /**
     * 대상 알림이 도착한 뒤 표지를 보내고, 표지까지 도착했을 때 대상이 정확히 한 번인지 본다. 표지는 A 의 발송자로 보낸다 — 두 인스턴스
     * 구독자가 모두 받으므로 어느 쪽 연결이든 같다.
     */
    private void assertReceivedExactlyOnce(CapturingSseEmitter emitter, long userId, long notificationId) {
        awaitUntil(() -> countOf(emitter, notificationId) >= 1);
        instanceASender.send(delivery(MARKER_NOTIFICATION_ID, userId));
        awaitUntil(() -> countOf(emitter, MARKER_NOTIFICATION_ID) >= 1);
        assertThat(countOf(emitter, notificationId)).isEqualTo(1);
    }

    private static long countOf(CapturingSseEmitter emitter, long notificationId) {
        return emitter.events().stream()
                .flatMap(event -> event.objects().stream())
                .filter(NotificationEventResponse.class::isInstance)
                .map(NotificationEventResponse.class::cast)
                .filter(body -> body.notificationId() == notificationId)
                .count();
    }

    private static NotificationDelivery delivery(long notificationId, long userId) {
        return new NotificationDelivery(
                notificationId, userId, NotificationType.RISK_CHANGE, 1024L, LocalDateTime.of(2026, 9, 14, 3, 0));
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("알림 이벤트가 " + WAIT + " 안에 연결에 도착하지 않았다");
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("대기 중 인터럽트");
        }
    }
}
