package com.duri.rentalplatform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합·매퍼 테스트가 공유하는 PostgreSQL 컨테이너 지원 클래스.
 *
 * <p>tech-stack.md §7의 버전 고정 정책에 따라 이미지를 {@code postgres:17-alpine}로 못박는다.
 * H2는 방언 차이로 통과·실패가 어긋나므로 사용하지 않는다(testing.md §1.1 통합 테스트).
 *
 * <p>{@code @ServiceConnection}이 컨테이너 접속 정보를 datasource로 자동 주입하므로,
 * 이 클래스를 {@code @Import}한 테스트는 별도 프로퍼티 설정 없이 실제 DB로 기동한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
