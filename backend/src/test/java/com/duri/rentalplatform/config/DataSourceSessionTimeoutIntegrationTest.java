package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 앱 커넥션의 세션 시간 제한(#402) — 읽기 분산을 끈 기본 구성. 앱이 쓰는 커넥션은 설정 파일의 값을 갖고, Flyway 는 그 풀을
 * 빌리지 않는다. 읽기 분산을 켠 구성(읽기용 풀 · 쓰기 노드 풀 · Flyway 세션 값)은 {@code ReplicaRoutingIntegrationTest} 가 본다.
 *
 * <p>컨텍스트 구성이 {@code BackendApplicationTests} 와 같아 컨텍스트 캐시를 함께 쓴다 — 컨텍스트를 더 띄우지 않는다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DataSourceSessionTimeoutIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Test
    @DisplayName("앱 커넥션의 statement_timeout 은 30s 다")
    void statementTimeout() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    @DisplayName("앱 커넥션의 idle_in_transaction_session_timeout 은 60초(1min)다")
    void idleInTransactionTimeout() {
        assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class)).isEqualTo("1min");
    }

    /**
     * 이 구성에서는 {@code @ServiceConnection} 이 Flyway 접속 정보를 따로 주므로 설정 파일의 {@code spring.flyway.url} 이 없어도
     * 통과한다 — 설정 파일 경로(운영과 같다)는 {@code ReplicaRoutingIntegrationTest} 가 본다. 여기서는 누가 Flyway 전용 데이터 소스로
     * 앱 풀을 지정하면({@code @FlywayDataSource}) 잡는다.
     */
    @Test
    @DisplayName("Flyway 는 앱 풀(Hikari)을 빌리지 않는다 - 마이그레이션의 세션 값이 풀로 새지 않는다")
    void flywayDoesNotUseAppPool() {
        assertThat(flyway.getConfiguration().getDataSource()).isNotInstanceOf(HikariDataSource.class);
    }
}
