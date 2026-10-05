package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslVerifyMode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 앱의 Redis 접속이 환경 변수에 따라 단일 노드 · Sentinel 로 갈리는지(INF-01 #404).
 *
 * <p>실제 application.yml 을 읽고, OS 환경 변수 자리를 테스트가 정한 맵으로 바꿔 운영 Compose 의 env_file 과 같은 경로로 값을
 * 넣는다. Redis 에 접속하지 않는다 — 연결 팩토리는 시작돼도 첫 명령 전에는 붙지 않으므로, 만들어진 클라이언트의 접속 URI 를 본다.
 */
class RedisSentinelConfigTest {

    private static final String NODES = "10.20.0.10:26379,10.20.1.10:26379,10.20.20.10:26379";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, SslAutoConfiguration.class))
            .withUserConfiguration(RedisSentinelConfig.class);

    @Test
    @DisplayName("Sentinel 변수가 없으면 지금처럼 host · port 단일 노드다")
    void standaloneWithoutSentinelEnv() {
        withEnv(Map.of("REDIS_HOST", "redis", "REDIS_PORT", "6379")).run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.isRedisSentinelAware()).isFalse();
            assertThat(factory.getHostName()).isEqualTo("redis");
            assertThat(factory.getPort()).isEqualTo(6379);
        });
    }

    @Test
    @DisplayName("Sentinel 변수가 빈 값으로 있어도(env_file 의 값 없는 줄) 단일 노드로 뜬다")
    void standaloneWithBlankSentinelEnv() {
        withEnv(Map.of(
                        "SPRING_DATA_REDIS_SENTINEL_MASTER", "",
                        "SPRING_DATA_REDIS_SENTINEL_NODES", "",
                        "SPRING_DATA_REDIS_SENTINEL_PASSWORD", ""))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.isRedisSentinelAware()).isFalse();
                    assertThat(factory.getHostName()).isEqualTo("localhost");
                });
    }

    @Test
    @DisplayName("Sentinel 변수가 있으면 Sentinel 로 주 Redis 를 찾고, 주 Redis 와 Sentinel 비밀번호를 따로 쓴다")
    void sentinelWithEnv() {
        withEnv(sentinelEnv()).run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.isRedisSentinelAware()).isTrue();

            RedisSentinelConfiguration config = factory.getSentinelConfiguration();
            assertThat(config.getMaster().getName()).isEqualTo("rental");
            assertThat(config.getSentinels())
                    .extracting(RedisNode::getHost, RedisNode::getPort)
                    .containsExactlyInAnyOrder(
                            tuple("10.20.0.10", 26379),
                            tuple("10.20.1.10", 26379),
                            tuple("10.20.20.10", 26379));
            assertThat(config.getPassword().get()).isEqualTo("data-secret".toCharArray());
            assertThat(config.getSentinelPassword().get()).isEqualTo("sentinel-secret".toCharArray());
        });
    }

    @Test
    @DisplayName("SSL 을 켜면 주 Redis 접속뿐 아니라 Sentinel 셋 접속에도 TLS · 호스트 검증이 걸린다 — 별도 설정 없이")
    void sslAppliesToSentinelConnections() {
        Map<String, Object> env = sentinelEnv();
        env.put("SPRING_DATA_REDIS_SSL_ENABLED", "true");
        env.put("SPRING_DATA_REDIS_SSL_BUNDLE", "redis");
        env.put("SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE", "classpath:redis-tls/test-ca.crt");

        withEnv(env).run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
            assertThat(factory.getClientConfiguration().getClientOptions())
                    .hasValueSatisfying(options -> assertThat(options.getSslOptions()).isNotNull());

            RedisURI uri = nativeUri(factory);
            assertThat(uri.getSentinelMasterId()).isEqualTo("rental");
            assertThat(uri.isSsl()).isTrue();
            assertThat(uri.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);

            List<RedisURI> sentinels = uri.getSentinels();
            assertThat(sentinels).hasSize(3);
            assertThat(sentinels).allSatisfy(sentinel -> {
                assertThat(sentinel.isSsl()).isTrue();
                assertThat(sentinel.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
                assertThat(password(sentinel)).isEqualTo("sentinel-secret");
            });
            assertThat(password(uri)).isEqualTo("data-secret");
        });
    }

    @Test
    @DisplayName("SSL 을 켜지 않으면 Sentinel 접속도 평문이다 — 위 시험이 SSL 설정 덕에 통과했음을 가른다")
    void plainSentinelWithoutSsl() {
        withEnv(sentinelEnv()).run(context -> {
            RedisURI uri = nativeUri(context.getBean(LettuceConnectionFactory.class));
            assertThat(uri.isSsl()).isFalse();
            assertThat(uri.getSentinels()).allSatisfy(sentinel -> assertThat(sentinel.isSsl()).isFalse());
        });
    }

    @Test
    @DisplayName("master 만 있고 nodes 가 비면 기동을 멈춘다 — 조용히 단일 노드로 떨어지지 않는다")
    void failsWhenOnlyMasterIsSet() {
        withEnv(Map.of("SPRING_DATA_REDIS_SENTINEL_MASTER", "rental", "SPRING_DATA_REDIS_SENTINEL_NODES", ""))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("Sentinel 설정이 반쪽"));
    }

    @Test
    @DisplayName("nodes 만 있고 master 가 비면 기동을 멈춘다")
    void failsWhenOnlyNodesAreSet() {
        withEnv(Map.of("SPRING_DATA_REDIS_SENTINEL_MASTER", "", "SPRING_DATA_REDIS_SENTINEL_NODES", NODES))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("Sentinel 설정이 반쪽"));
    }

    private static Map<String, Object> sentinelEnv() {
        Map<String, Object> env = new HashMap<>();
        env.put("REDIS_PASSWORD", "data-secret");
        env.put("SPRING_DATA_REDIS_SENTINEL_MASTER", "rental");
        env.put("SPRING_DATA_REDIS_SENTINEL_NODES", NODES);
        env.put("SPRING_DATA_REDIS_SENTINEL_PASSWORD", "sentinel-secret");
        return env;
    }

    /** application.yml 을 올리고 OS 환경 변수 자리를 주어진 맵으로 바꾼다 — 개발자 PC 의 실제 환경 변수가 끼어들지 않는다. */
    private ApplicationContextRunner withEnv(Map<String, Object> env) {
        return runner.withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
            applicationYml().forEach(sources::addLast);
        });
    }

    private static List<PropertySource<?>> applicationYml() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RedisURI nativeUri(LettuceConnectionFactory factory) {
        RedisClient client = (RedisClient) factory.getRequiredNativeClient();
        return (RedisURI) ReflectionTestUtils.getField(client, "redisURI");
    }

    private static String password(RedisURI uri) {
        RedisCredentials credentials = uri.getCredentialsProvider().resolveCredentials().block();
        return new String(credentials.getPassword());
    }
}
