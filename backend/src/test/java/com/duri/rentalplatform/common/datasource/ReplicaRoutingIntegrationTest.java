package com.duri.rentalplatform.common.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertyMapClustersRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.service.PropertyQueryService;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 읽기 분산을 켠 상태의 라우팅(#343). 컨테이너는 이 클래스 전용 static 이다 — 공유 {@code TestcontainersConfiguration} 은
 * {@code @Bean} 으로 컨테이너를 내놓아 컨텍스트가 닫힐 때 close 되므로, 켠 컨텍스트가 닫히면 다른 테스트의 컨테이너가 죽는다.
 * 여기서는 컨테이너를 빈으로 등록하지 않아 컨텍스트 종료가 컨테이너에 닿지 않는다.
 *
 * <p>어느 서버인지는 {@code current_database()} 로 가른다 — primary 는 primarydb, replica 는 replicadb. replica 는 별도 서버라
 * 스키마가 없다(Flyway 는 primary 에만 돈다). 그래서 표시된 실제 서비스 메서드가 replica 로 가면 테이블이 없다는 오류가 난다.
 *
 * <p>세션 시간 제한(#402)도 여기서 본다. 접속 정보를 {@code @ServiceConnection} 이 아니라 설정 값({@code spring.datasource.url})으로
 * 주므로 Flyway 가 운영과 같은 경로({@code spring.flyway.url})로 커넥션을 얻는다 — 공유 컨테이너 구성은 Flyway 접속 정보를 따로
 * 주어 이 경로를 지나지 않는다.
 */
@Tag("integration")
@SpringBootTest
@Import(ReplicaRoutingIntegrationTest.Probes.class)
class ReplicaRoutingIntegrationTest {

    private static final PostgreSQLContainer<?> PRIMARY =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine")).withDatabaseName("primarydb");
    private static final PostgreSQLContainer<?> REPLICA =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine")).withDatabaseName("replicadb");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        PRIMARY.start();
        REPLICA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PRIMARY::getJdbcUrl);
        registry.add("spring.datasource.username", PRIMARY::getUsername);
        registry.add("spring.datasource.password", PRIMARY::getPassword);
        registry.add("app.datasource.replica.enabled", () -> "true");
        registry.add("app.datasource.replica.hosts", () -> REPLICA.getHost() + ":" + REPLICA.getFirstMappedPort());
        registry.add("app.datasource.replica.url", REPLICA::getJdbcUrl);
        registry.add("app.datasource.replica.username", REPLICA::getUsername);
        registry.add("app.datasource.replica.password", REPLICA::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** 어느 서버인지 돌려주는 시험용 빈. 표시 유무 · 트랜잭션 조합을 만든다. */
    static class ProbeReader {
        private final JdbcTemplate jdbc;

        ProbeReader(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @ReplicaRead
        @Transactional(readOnly = true)
        public String markedDb() {
            return jdbc.queryForObject("select current_database()", String.class);
        }

        @Transactional(readOnly = true)
        public String plainDb() {
            return jdbc.queryForObject("select current_database()", String.class);
        }

        @ReplicaRead
        @Transactional(readOnly = true)
        public String markedSetting(String name) {
            return jdbc.queryForObject("select current_setting(?)", String.class, name);
        }

        @Transactional(readOnly = true)
        public String plainSetting(String name) {
            return jdbc.queryForObject("select current_setting(?)", String.class, name);
        }
    }

    /** 마이그레이션이 끝난 시점에 Flyway 자기 커넥션의 세션 값을 적어 둔다. Boot 가 {@link Callback} 빈을 Flyway 에 붙인다. */
    static class FlywaySessionProbe implements Callback {
        final Map<String, String> settings = new ConcurrentHashMap<>();

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            try (Statement s = context.getConnection().createStatement()) {
                for (String name : new String[] {"statement_timeout", "idle_in_transaction_session_timeout"}) {
                    try (ResultSet rs = s.executeQuery("select current_setting('" + name + "')")) {
                        rs.next();
                        settings.put(name, rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String getCallbackName() {
            return "flywaySessionProbe";
        }
    }

    static class ProbeWriter {
        private final JdbcTemplate jdbc;
        private final ProbeReader reader;

        ProbeWriter(JdbcTemplate jdbc, ProbeReader reader) {
            this.jdbc = jdbc;
            this.reader = reader;
        }

        /** 쓰기 트랜잭션 안에서 표시된 메서드를 부른다. 쓰기가 먼저라 읽기 전용 풀로 가면 실패한다. */
        @Transactional
        public String writeThenMarkedRead() {
            jdbc.execute("create table if not exists probe_write(id int)");
            jdbc.update("insert into probe_write values (1)");
            return reader.markedDb();
        }

        /**
         * 표시된 읽기가 트랜잭션의 첫 조회다. 지연 프록시라 이 시점에 커넥션이 정해지므로, 관점이 표시를 걸면 읽기 전용 풀의
         * 커넥션이 트랜잭션에 묶여 뒤이은 쓰기가 실패한다.
         */
        @Transactional
        public String markedReadThenWrite() {
            String db = reader.markedDb();
            jdbc.execute("create table if not exists probe_write(id int)");
            jdbc.update("insert into probe_write values (2)");
            return db;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        ProbeReader probeReader(JdbcTemplate jdbc) {
            return new ProbeReader(jdbc);
        }

        @Bean
        ProbeWriter probeWriter(JdbcTemplate jdbc, ProbeReader reader) {
            return new ProbeWriter(jdbc, reader);
        }

        @Bean
        FlywaySessionProbe flywaySessionProbe() {
            return new FlywaySessionProbe();
        }
    }

    @Autowired
    ProbeReader reader;
    @Autowired
    ProbeWriter writer;
    @Autowired
    PropertyQueryService propertyQueryService;
    @Autowired
    FlywaySessionProbe flywaySessionProbe;
    @Autowired
    Flyway flyway;

    @Test
    @DisplayName("표시한 메서드는 replica 로 간다")
    void markedGoesToReplica() {
        assertThat(reader.markedDb()).isEqualTo("replicadb");
    }

    @Test
    @DisplayName("표시 없는 메서드는 primary 로 가고, 표시한 호출 뒤에도 남지 않는다")
    void plainGoesToPrimaryAndMarkDoesNotLeak() {
        assertThat(reader.markedDb()).isEqualTo("replicadb");
        assertThat(reader.plainDb()).isEqualTo("primarydb");
    }

    @Test
    @DisplayName("쓰기 트랜잭션 안에서 표시된 메서드를 불러도 primary 에 머문다")
    void insideWriteTransactionStaysPrimary() {
        assertThat(writer.writeThenMarkedRead()).isEqualTo("primarydb");
    }

    @Test
    @DisplayName("트랜잭션의 첫 조회가 표시된 메서드여도 그 트랜잭션은 primary 에 묶여 뒤이은 쓰기가 성공한다")
    void markedFirstReadInWriteTransactionStaysPrimary() {
        assertThat(writer.markedReadThenWrite()).isEqualTo("primarydb");
    }

    @Test
    @DisplayName("표시된 서비스 메서드 셋(집계 · 목록 · 지도 묶음)은 replica 로 간다 - 스키마가 없어 테이블 오류가 난다")
    void markedServiceMethodsHitReplica() {
        DistrictCountRequest filter = new DistrictCountRequest(null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> propertyQueryService.getDistrictCounts(filter))
                .hasRootCauseInstanceOf(SQLException.class);

        PropertySearchRequest search = new PropertySearchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> propertyQueryService.search(search))
                .hasRootCauseInstanceOf(SQLException.class);

        PropertyMapClustersRequest clusters = new PropertyMapClustersRequest(null, null, null, null, null, null, null,
                null, null, 37.4, 37.7, 126.8, 127.2);
        assertThatThrownBy(() -> propertyQueryService.getMapClusters(clusters))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("표시 없는 getDetail 은 primary 로 가서 스키마가 있는 쪽에서 없음 응답을 낸다")
    void detailGoesToPrimary() {
        assertThatThrownBy(() -> propertyQueryService.getDetail(-1L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND));
    }

    @Test
    @DisplayName("Flyway 는 primary 에만 돌았다 - replica 에는 flyway_schema_history 가 없다")
    void flywayRanOnPrimaryOnly() throws Exception {
        assertThat(regclass(PRIMARY)).isNotNull();
        assertThat(regclass(REPLICA)).isNull();
    }

    @Test
    @DisplayName("쓰기 노드 풀 · 읽기용 풀 모두 세션 시간 제한이 30s · 1min 이다 - 읽기용 풀은 기본 풀 설정을 입는다")
    void bothPoolsHaveSessionTimeouts() {
        assertThat(reader.plainSetting("statement_timeout")).isEqualTo("30s");
        assertThat(reader.plainSetting("idle_in_transaction_session_timeout")).isEqualTo("1min");
        assertThat(reader.markedSetting("statement_timeout")).isEqualTo("30s");
        assertThat(reader.markedSetting("idle_in_transaction_session_timeout")).isEqualTo("1min");
    }

    @Test
    @DisplayName("Flyway 마이그레이션 세션은 시간 제한이 0 이다 - 앱 풀을 빌리지 않고 init-sqls 가 걸린 자기 커넥션이다")
    void flywaySessionHasNoTimeouts() {
        assertThat(flyway.getConfiguration().getDataSource()).isNotInstanceOf(HikariDataSource.class);
        assertThat(flywaySessionProbe.settings)
                .containsEntry("statement_timeout", "0")
                .containsEntry("idle_in_transaction_session_timeout", "0");
    }

    private static String regclass(PostgreSQLContainer<?> container) throws Exception {
        try (Connection c = container.createConnection("");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select to_regclass('public.flyway_schema_history')::text")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
