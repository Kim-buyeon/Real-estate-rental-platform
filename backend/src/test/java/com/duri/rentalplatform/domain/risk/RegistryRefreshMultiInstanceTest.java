package com.duri.rentalplatform.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.BackendApplication;
import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.RegistryRefreshJobLauncher;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * RISK-08 등기 재조회 배치의 날짜 락이 인스턴스를 가로질러 성립하는지에 대한 다중 인스턴스 검증 — testing.md 1.1 「다중 인스턴스
 * 테스트」의 배치 분산 락.
 *
 * <p>두 프로세스가 같은 시각에 스케줄을 부른다. 「오늘 실행했는가」가 프로세스 메모리에 있으면 둘 다 실행해 관심 매물 등기를 두 번
 * 떼고, 단일 인스턴스 테스트는 그래도 전부 통과한다. 여기서는 컨텍스트 둘이 같은 날짜로 배치 진입점을 동시에 부르면 한쪽만 실행되는지
 * 본다. 컨텍스트 둘 · 같은 컨테이너 구성과 그 이유는 {@code TokenRotationMultiInstanceTest} 와 같다.
 *
 * <p><b>겹침을 확정하는 방법</b> — 먼저 락을 잡은 쪽이 끝나 락을 풀어 버리면 늦은 쪽도 실행되므로, 「동시에」가 우연에 달린다.
 * 그래서 테스트가 별도 커넥션으로 {@code building_registry} 에 배타 테이블 락을 걸어 둔다. 날짜 락을 잡은 쪽은 첫 매물의 등기를
 * 비교하는 조회에서 멈추고, 그동안 다른 쪽의 시도는 반드시 날짜 락에 막힌다. 막힌 쪽이 끝난 것을 확인한 뒤 테이블 락을 푼다.
 *
 * <p>실행 여부는 반환된 집계로 본다. 날짜 락을 못 잡은 쪽은 Job 을 시작하지 않으므로 집계가 없고 503 이 난다. 배치 스텝은 매물
 * 단위 실패를 전부 집계로 삼키고 스텝 실패는 503 이 아닌 예외로 알리므로, 진입점 밖으로 나오는 503 은 날짜 락뿐이다. 진입점은
 * Spring Batch 로 옮긴 뒤에도 배치 시작기의 {@code run(date)} 다 — 날짜 락이 거기 붙어 있고, 스케줄이 그것을 부른다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 인스턴스 B 는 자기 커넥션으로 A 의 미커밋 픽스처를 볼 수 없다. 대신 앞에서 관심
 * 매물 테이블과 날짜 락 키를 비운다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryRefreshMultiInstanceTest {

    private static final int REDIS_EXPOSED_PORT = 6379;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
    private static final String DATE_LOCK_KEY = "risk:batch:registry-refresh:" + DATE;
    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    RegistryRefreshJobLauncher instanceALauncher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    private ConfigurableApplicationContext instanceBContext;
    private RegistryRefreshJobLauncher instanceBLauncher;

    @BeforeAll
    void startSecondInstance() {
        instanceBContext = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .run(sharedInfrastructureArguments());
        instanceBLauncher = instanceBContext.getBean(RegistryRefreshJobLauncher.class);
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
                "--risk.batch.registry-refresh.enabled=false"
        };
    }

    @BeforeEach
    void prepareSingleWishlistedProperty() {
        // 배치 대상은 관심 매물 전체라 앞 테스트가 커밋한 관심 매물이 섞이면 대상 건수가 흔들린다. 관심 매물을 참조하는 알림 행이
        // 있을 수 있어 CASCADE 다.
        jdbcTemplate.execute("TRUNCATE TABLE wishlist CASCADE");
        stringRedisTemplate.delete(DATE_LOCK_KEY);

        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('배치', 800) RETURNING user_id", Long.class);
        long propertyId = insertProperty();
        jdbcTemplate.update("""
                INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition, created_at)
                VALUES (?, ?, TRUE, 'RISK_AND_REGISTRY', ?)
                """, userId, propertyId, LocalDateTime.of(2026, 9, 1, 10, 0));
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
        CompletableFuture<RegistryRefreshReport> onA;
        CompletableFuture<RegistryRefreshReport> onB;

        // 공용 풀을 쓰지 않는다 — 코어가 적은 CI 에서는 병렬도가 1 이라 두 호출이 동시에 시작하지 못하고 시작 장벽에서 멈춘다.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (threads; Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE building_registry IN ACCESS EXCLUSIVE MODE");
            }
            try {
                onA = CompletableFuture.supplyAsync(() -> runAfter(start, instanceALauncher), threads);
                onB = CompletableFuture.supplyAsync(() -> runAfter(start, instanceBLauncher), threads);

                // 먼저 끝나는 쪽은 날짜 락에 막힌 쪽이다. 잡은 쪽은 테이블 락에서 기다리고 있다.
                CompletableFuture.anyOf(onA, onB)
                        .handle((result, failure) -> null)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(List.of(onA, onB)).filteredOn(CompletableFuture::isDone).hasSize(1);
                // 겹침이 실제로 일어났음을 확인한다 — 잡은 쪽이 아직 락 안에서 멈춰 있으므로 날짜 락 키가 남아 있어야 한다.
                // 키가 없으면 먼저 끝난 쪽이 락을 풀고 나간 뒤라, 늦은 쪽이 막힌 이유가 날짜 락이 아닐 수 있다.
                assertThat(stringRedisTemplate.hasKey(DATE_LOCK_KEY)).isTrue();
            } finally {
                // 테이블 락을 풀어 잡은 쪽을 끝까지 돌린다. 자원은 역순으로 닫히므로 커넥션 다음 스레드 풀이 두 호출의 끝을 기다린다.
                blocker.rollback();
            }
        }

        List<Outcome> outcomes = List.of(outcomeOf(onA), outcomeOf(onB));
        assertThat(outcomes).filteredOn(outcome -> outcome.report() != null)
                .singleElement()
                .satisfies(outcome -> {
                    // 픽스처 매물은 등기가 없어 첫 수집(변동)이고, 재분석까지 실패 없이 끝나야 한다.
                    assertThat(outcome.report().getTargets()).isEqualTo(1);
                    assertThat(outcome.report().getModified()).isEqualTo(1);
                    assertThat(outcome.report().getFailed()).isZero();
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

    private static RegistryRefreshReport runAfter(CyclicBarrier start, RegistryRefreshJobLauncher jobLauncher) {
        try {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 시작 대기 실패", e);
        }
        return jobLauncher.run(DATE);
    }

    private static Outcome outcomeOf(CompletableFuture<RegistryRefreshReport> future) throws Exception {
        try {
            return new Outcome(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
            return new Outcome(null, cause);
        }
    }

    private record Outcome(RegistryRefreshReport report, Throwable failure) {
    }

    private long codeId(String group, String value) {
        return jdbcTemplate.queryForObject(
                "SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private long insertProperty() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, registered_at)
                VALUES ('서울특별시 테스트1구 시험로 1', '테스트1구', '김임대', ?, ?, ?, 230000000, 0, 340000000,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, 37.5, 126.8, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), LocalDateTime.of(2026, 6, 30, 9, 0));
    }
}
