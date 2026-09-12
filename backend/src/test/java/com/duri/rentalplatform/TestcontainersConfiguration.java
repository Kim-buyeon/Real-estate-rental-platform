package com.duri.rentalplatform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합·매퍼 테스트가 공유하는 PostgreSQL·Redis 컨테이너 지원 클래스.
 *
 * <p>tech-stack.md §7의 버전 고정 정책에 따라 이미지를 {@code postgres:17-alpine}·{@code redis:7-alpine}로
 * 못박는다. H2 등 인메모리 대체는 방언·동작 차이로 통과·실패가 어긋나므로 사용하지 않는다
 * (testing.md §1.1 통합 테스트).
 *
 * <p>Redis를 여기 두는 이유는 리프레시 토큰 보관과 재발급 시 회전이 만료·삭제까지 실제 Redis에 의존하기
 * 때문이다 — 여러 테스트가 같은 컨테이너를 필요로 한다.
 *
 * <p>{@code @ServiceConnection}이 두 컨테이너의 접속 정보를 자동 구성에 넘기므로, 이 클래스를
 * {@code @Import}한 테스트는 별도 설정 없이 실제 DB·Redis로 기동한다. 다만 이것은 프로퍼티를 덮어쓰는
 * 방식이 아니라 커넥션 정보 빈을 등록해 자동 구성이 그 빈을 우선하게 하는 방식이다. 따라서
 * {@code Environment}에서 {@code spring.data.redis.port}를 읽으면 컨테이너 포트가 아니라 설정 파일의
 * 기본값이 나온다. 접속 대상을 확인하려면 연결 팩토리를 보아야 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    /**
     * Redis는 전용 Testcontainers 모듈 없이 코어 {@link GenericContainer}로 띄운다. 이때
     * {@code name = "redis"}가 필요하다 — 서비스 커넥션 팩토리는 이미지 이름으로 Redis를 식별하는데,
     * {@code @Bean}으로 정의한 컨테이너는 기동 전에 이미지 이름을 알 수 없어 이름을 명시하지 않으면
     * 대응하는 팩토리를 찾지 못하고 컨텍스트가 뜨지 않는다. 포트를 노출해야 매핑 포트를 읽을 수 있다.
     */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
