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
 * <p><b>컨테이너는 static 싱글턴이다.</b> {@code @Bean} 메서드가 매번 새 인스턴스를 만들면 스프링
 * 컨텍스트마다 컨테이너가 새로 뜬다. 통합·매퍼 테스트의 컨텍스트가 여러 벌로 갈리므로 그만큼 기동 비용이
 * 곱해진다. 같은 인스턴스를 돌려주면 JVM당 한 벌만 뜨고, 컨텍스트 캐시가 살아 있는 동안 공유된다.
 *
 * <p><b>{@code @DirtiesContext}를 붙이지 않는다.</b> {@code @Bean}으로 등록된 컨테이너는
 * {@code AutoCloseable}이라 스프링이 파괴 메서드로 {@code close()}를 추론한다
 * ({@code DisposableBeanAdapter.inferDestroyMethodsIfNecessary}). 컨텍스트가 닫히면 공유 컨테이너가
 * 멈추므로, 캐시에 남아 있는 다른 컨텍스트까지 함께 죽는다. 지금은 컨텍스트가 JVM 종료 시에만 닫혀
 * 문제가 없다 — 컨텍스트가 4벌이라 캐시 상한 32에 걸려 축출되지도 않는다. 이 전제가 깨지는 순간
 * 조용히 무너지므로 전제를 여기 적어 둔다.
 *
 * <p>gradle 실행 사이의 컨테이너 재사용({@code withReuse})은 넣지 않았다. 켠 상태와 끈 상태를 연속으로
 * 측정했을 때 차이가 없었고, 재사용은 앞 실행의 데이터가 남아 테스트 격리를 흐린다.
 *
 * <p>{@code @ServiceConnection}이 두 컨테이너의 접속 정보를 자동 구성에 넘기므로, 이 클래스를
 * {@code @Import}한 테스트는 별도 설정 없이 실제 DB·Redis로 기동한다. 다만 이것은 프로퍼티를 덮어쓰는
 * 방식이 아니라 커넥션 정보 빈을 등록해 자동 구성이 그 빈을 우선하게 하는 방식이다. 따라서
 * {@code Environment}에서 {@code spring.data.redis.port}를 읽으면 컨테이너 포트가 아니라 설정 파일의
 * 기본값이 나온다. 접속 대상을 확인하려면 연결 팩토리를 보아야 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGRES;
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
        return REDIS;
    }
}
