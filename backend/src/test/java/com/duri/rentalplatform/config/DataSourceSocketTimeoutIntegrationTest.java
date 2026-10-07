package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 소켓 읽기 제한(socketTimeout)이 응답이 끊긴 DB 에서 질의를 끝내는지. DB 컨테이너를 얼려(docker pause) 응답이 오지 않게 한다.
 *
 * <p>공유 컨테이너({@code TestcontainersConfiguration})를 멈추면 다른 테스트가 깨지므로 이 클래스 전용 컨테이너를 쓴다. 스프링
 * 컨텍스트는 띄우지 않는다 — 순수 JDBC 로 설정 파일과 같은 드라이버 속성({@code socketTimeout})만 검증한다.
 */
@Tag("integration")
class DataSourceSocketTimeoutIntegrationTest {

    private static final int SOCKET_TIMEOUT_SECONDS = 2;
    private static final Duration UPPER_BOUND = Duration.ofSeconds(10);

    private static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void start() {
        DB.start();
    }

    @AfterAll
    static void stop() {
        DB.stop();
    }

    private static Connection connect(String socketTimeout) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DB.getUsername());
        props.setProperty("password", DB.getPassword());
        if (socketTimeout != null) {
            props.setProperty("socketTimeout", socketTimeout);
        }
        return DriverManager.getConnection(DB.getJdbcUrl(), props);
    }

    @Test
    @DisplayName("응답이 끊긴 DB 에서 질의는 socketTimeout 이 지나면 SQLException 으로 상한 안에 끝난다")
    void queryEndsWithinBoundWhenServerStopsResponding() throws Exception {
        try (Connection connection = connect(String.valueOf(SOCKET_TIMEOUT_SECONDS));
                Statement statement = connection.createStatement()) {
            assertThat(statement.execute("select 1")).isTrue();

            DB.getDockerClient().pauseContainerCmd(DB.getContainerId()).exec();
            long startedAt = System.nanoTime();
            try {
                assertThatThrownBy(() -> statement.execute("select 2")).isInstanceOf(SQLException.class);
            } finally {
                DB.getDockerClient().unpauseContainerCmd(DB.getContainerId()).exec();
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(UPPER_BOUND);
            // 제한이 실제로 작동했다 — 제한보다 일찍 끝났다면 다른 이유로 실패한 것이다.
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS - 1));
        }
    }

    @Test
    @DisplayName("제한 없는 연결은 같은 조건에서 상한을 넘겨 기다린다 - 위 시험이 제한 덕에 끝난다는 대조")
    void controlWithoutSocketTimeoutKeepsWaiting() throws Exception {
        try (Connection connection = connect(null);
                Statement statement = connection.createStatement()) {
            statement.execute("select 1");

            DB.getDockerClient().pauseContainerCmd(DB.getContainerId()).exec();
            java.util.concurrent.CompletableFuture<Boolean> query = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return statement.execute("select 2");
                } catch (SQLException e) {
                    return false;
                }
            });
            try {
                Thread.sleep(UPPER_BOUND.toMillis());
                assertThat(query).isNotDone();
            } finally {
                DB.getDockerClient().unpauseContainerCmd(DB.getContainerId()).exec();
            }
            assertThat(query.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
