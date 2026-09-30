package com.duri.rentalplatform.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.BackendApplication;
import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.PropertyRefreshJobLauncher;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * RISK-08 매물 · 시세 갱신 배치의 날짜 락이 인스턴스를 가로질러 성립하는지에 대한 다중 인스턴스 검증 — testing.md 1.1 「다중
 * 인스턴스 테스트」의 배치 분산 락. 컨텍스트 둘 · 같은 컨테이너 구성은 {@code RegistryRefreshMultiInstanceTest} 와 같다.
 *
 * <p><b>겹침을 확정하는 방법</b> — 등기 재조회 배치 테스트는 테이블 락으로 잡은 쪽을 붙든다. 이 배치는 첫 스텝이 외부 호출(실거래가)
 * 이므로 실거래가 클라이언트를 테스트용으로 바꿔 끼워, 날짜 락을 잡은 쪽이 첫 호출에서 멈추게 한다. 그동안 다른 쪽의 시도는 반드시 날짜
 * 락에 막힌다. 막힌 쪽이 끝난 것을 확인한 뒤 멈춘 쪽을 놓아 준다. 클라이언트는 어느 조회에도 빈 목록을 돌려주므로 공유 컨테이너에
 * 매물이 적재되지 않는다(Mock 클라이언트는 한 회차에 2,400건을 만든다).
 *
 * <p>실행 여부는 반환된 집계로 본다. 날짜 락을 못 잡은 쪽은 Job 을 시작하지 않아 집계가 없고 503 이다. 스텝 실패는 503 이 아닌 예외로
 * 알리므로 진입점 밖으로 나오는 503 은 날짜 락뿐이다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 인스턴스 B 는 A 의 미커밋 데이터를 볼 수 없다. 대신 앞에서 날짜 락 키를 비운다.
 */
@Tag("integration")
@SpringBootTest
@Import({TestcontainersConfiguration.class, PropertyRefreshMultiInstanceTest.GatedClientConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PropertyRefreshMultiInstanceTest {

    private static final int REDIS_EXPOSED_PORT = 6379;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String DATE_LOCK_KEY = "property:batch:refresh:" + DATE;
    private static final long TIMEOUT_SECONDS = 60;

    /** 날짜 락을 잡은 쪽이 실거래가 조회에 들어왔음을 알린다 · 테스트가 놓아 줄 때까지 붙든다. 두 컨텍스트가 공유한다. */
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    private static volatile CountDownLatch release = new CountDownLatch(1);

    @Autowired
    PropertyRefreshJobLauncher instanceALauncher;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    private ConfigurableApplicationContext instanceBContext;
    private PropertyRefreshJobLauncher instanceBLauncher;

    @BeforeAll
    void startSecondInstance() {
        instanceBContext = new SpringApplicationBuilder(BackendApplication.class, GatedClientConfig.class)
                .web(WebApplicationType.NONE)
                .run(sharedInfrastructureArguments());
        instanceBLauncher = instanceBContext.getBean(PropertyRefreshJobLauncher.class);
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
                // 스케줄이 테스트 도중 돌지 않게 한다. Gradle 실행은 시스템 속성으로 이미 끄지만 IDE 실행에도 걸리게 둔다.
                "--risk.batch.registry-refresh.enabled=false",
                "--property.batch.refresh.enabled=false"
        };
    }

    @BeforeEach
    void resetGate() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        stringRedisTemplate.delete(DATE_LOCK_KEY);
    }

    @AfterEach
    void openGateAndClearLock() {
        // 검증이 중간에 실패해도 붙들린 스레드가 남지 않게 한다.
        release.countDown();
        stringRedisTemplate.delete(DATE_LOCK_KEY);
    }

    @Test
    @DisplayName("인스턴스 A와 B는 서로 다른 컨텍스트이고 배치 시작기 빈도 서로 다르다")
    void instancesAreDistinct() {
        // 같은 빈이면 아래 테스트는 한 인스턴스 안의 경합을 보면서 초록으로 남는다.
        assertThat(instanceBLauncher).isNotSameAs(instanceALauncher);
    }

    @Test
    @DisplayName("두 인스턴스가 같은 날짜로 동시에 배치를 부르면 한쪽만 실행하고 다른 쪽은 날짜 락에 막혀 503 이다")
    void onlyOneInstanceRunsForSameDate() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        CompletableFuture<PropertyRefreshReport> onA;
        CompletableFuture<PropertyRefreshReport> onB;

        // 공용 풀을 쓰지 않는다 — 코어가 적은 CI 에서는 병렬도가 1 이라 두 호출이 동시에 시작하지 못하고 시작 장벽에서 멈춘다.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (threads) {
            try {
                onA = CompletableFuture.supplyAsync(() -> runAfter(start, instanceALauncher), threads);
                onB = CompletableFuture.supplyAsync(() -> runAfter(start, instanceBLauncher), threads);

                // 잡은 쪽이 실거래가 조회에서 멈춘 것을 확인한 뒤, 먼저 끝나는 쪽은 날짜 락에 막힌 쪽이다.
                assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                CompletableFuture.anyOf(onA, onB)
                        .handle((result, failure) -> null)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(List.of(onA, onB)).filteredOn(CompletableFuture::isDone).hasSize(1);
                // 겹침이 실제로 일어났음을 확인한다 — 잡은 쪽이 아직 락 안에서 멈춰 있으므로 날짜 락 키가 남아 있어야 한다.
                assertThat(stringRedisTemplate.hasKey(DATE_LOCK_KEY)).isTrue();
            } finally {
                // 잡은 쪽을 끝까지 돌린다. try-with-resources 가 두 호출의 끝을 기다린다.
                release.countDown();
            }
        }

        List<Outcome> outcomes = List.of(outcomeOf(onA), outcomeOf(onB));
        assertThat(outcomes).filteredOn(outcome -> outcome.report() != null)
                .singleElement()
                .satisfies(outcome -> {
                    // 클라이언트가 빈 목록만 주므로 적재는 아무것도 하지 않는다. 판정 스텝은 정상 종료해야 한다.
                    assertThat(outcome.report().getNewProperties()).isZero();
                    assertThat(outcome.report().getPriceChangedProperties()).isZero();
                    assertThat(outcome.report().getLoadReport().getFetched()).isZero();
                });
        assertThat(outcomes).filteredOn(outcome -> outcome.report() == null)
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.failure())
                        .isInstanceOf(BusinessException.class)
                        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        // 실행한 쪽이 끝나면 날짜 락은 풀린다.
        assertThat(stringRedisTemplate.hasKey(DATE_LOCK_KEY)).isFalse();
    }

    private static PropertyRefreshReport runAfter(CyclicBarrier start, PropertyRefreshJobLauncher jobLauncher) {
        try {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 시작 대기 실패", e);
        }
        return jobLauncher.run(DATE);
    }

    private static Outcome outcomeOf(CompletableFuture<PropertyRefreshReport> future) throws Exception {
        try {
            return new Outcome(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
            return new Outcome(null, cause);
        }
    }

    private record Outcome(PropertyRefreshReport report, Throwable failure) {
    }

    /** 실거래가 클라이언트를 바꿔 끼운다. 첫 호출에서 알리고 놓아 줄 때까지 멈춘 뒤 빈 목록을 돌려준다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GatedClientConfig {

        @Bean
        @Primary
        RentTransactionClient gatedRentTransactionClient() {
            return new RentTransactionClient() {
                @Override
                public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
                    entered.countDown();
                    try {
                        if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("놓아 주는 신호가 오지 않았다");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("대기 중 인터럽트", e);
                    }
                    return List.of();
                }
            };
        }
    }
}
