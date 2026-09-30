package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 건축HUB 초당 한도 응답이 서킷을 열지 않는지를 <b>실제 프록시(@Retry · @CircuitBreaker)와 {@code application.yml} 설정</b>으로
 * 확인한다(#318). 단위 테스트는 {@code new} 로 만든 클라이언트라 애노테이션 · {@code ignore-exceptions} · 폴백 선택이 걸리지 않는다.
 *
 * <p>제공처는 테스트 안에 띄운 JDK HTTP 서버다 — 외부로 나가지 않는다. 연동은 {@code mode=real} 이고 인증키는 인코딩 키 형태의
 * 더미다. 일일 상한은 목으로 둔다 — 실제 빈이면 공유 Redis 의 오늘 카운터를 쓴다. 초당 상한은 테스트 호출이 걸리지 않게 크게 둔다.
 *
 * <p>2026-09-30 14:29 운영에서 초당 한도 응답을 실패로 세어 서킷이 열리고 전 조회가 503 이 됐다. 서킷 최소 호출 수(10)를 넘겨
 * 불러 보고, 대조로 장애 응답은 여전히 실패로 세는지 본다 — 프록시가 안 걸려 통과하는 헛된 테스트가 아님을 확인한다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildingLedgerCircuitIntegrationTest {

    private static final String PER_SECOND_LIMIT_JSON = """
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
            "errMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR",
            "returnAuthMsg":"초당 서비스 요청제한 횟수 초과","returnReasonCode":"99"}}}
            """;
    private static final String FAILURE_JSON = """
            {"response":{"header":{"resultCode":"99","resultMsg":"APPLICATION ERROR"}}}
            """;

    private static final AtomicReference<String> RESPONSE = new AtomicReference<>(PER_SECOND_LIMIT_JSON);
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();

    private static final BuildingLedgerLookup LOOKUP = new BuildingLedgerLookup(1024L,
            new PropertyNaturalKey("서울특별시 시험구 시험길 19", new BigDecimal("84.90"), 7, 300_000_000L, 0L),
            "김임대", PropertyType.APARTMENT, new LedgerLookupKey("11110", "17400", "0702", "0000"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("external.building-ledger.mode", () -> "real");
        registry.add("external.building-ledger.base-url",
                () -> "http://localhost:" + SERVER.getAddress().getPort() + "/svc");
        registry.add("external.building-ledger.api-key", () -> "test%2Bkey%3D%3D");
        registry.add("external.building-ledger.per-second-limit", () -> "1000");
    }

    @MockitoBean
    BuildingLedgerDailyQuota dailyQuota;

    @Autowired
    BuildingLedgerClient client;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        when(dailyQuota.tryAcquire()).thenReturn(true);
        when(dailyQuota.remaining()).thenReturn(100L);
        circuitBreaker = circuitBreakerRegistry.circuitBreaker(BuildingLedgerClient.RESILIENCE_INSTANCE);
        circuitBreaker.reset();
        HITS.set(0);
        RESPONSE.set(PER_SECOND_LIMIT_JSON);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("초당 한도 응답을 최소 호출 수보다 많이 받아도 서킷은 닫혀 있고 실패로 세지 않으며, 재시도하지 않는다")
    void perSecondLimitDoesNotOpenCircuit() {
        assertThat(client).isInstanceOf(RealBuildingLedgerClient.class);
        int calls = 15;

        for (int i = 0; i < calls; i++) {
            assertThatThrownBy(() -> client.fetch(LOOKUP)).isInstanceOf(BuildingLedgerRateLimitedException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(HITS.get()).isEqualTo(calls);
    }

    @Test
    @DisplayName("대조: 장애 응답(resultCode 99)은 재시도 후 503 이고 서킷이 실패로 센다")
    void failureStillCounts() {
        RESPONSE.set(FAILURE_JSON);

        assertThatThrownBy(() -> client.fetch(LOOKUP)).isInstanceOf(BusinessException.class);

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isPositive();
        assertThat(HITS.get()).isGreaterThan(1);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/svc", exchange -> {
                HITS.incrementAndGet();
                byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
