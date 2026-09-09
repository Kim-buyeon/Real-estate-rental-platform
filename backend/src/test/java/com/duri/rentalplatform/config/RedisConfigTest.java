package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * RedisConfig의 RedisTemplate 연결·직렬화 설정 검증.
 *
 * <p>실제 Redis 7 컨테이너를 기동해(H2 격 인메모리 대체 없이) 값이 왕복되는지와 JSON으로 저장되는지를 확인한다
 * (testing.md §1.1 통합 테스트, tech-stack.md §7 {@code redis:7-alpine} 고정).
 *
 * <p>전체 컨텍스트가 뜨므로 {@code ddl-auto=validate}·Flyway가 함께 동작한다. 따라서 기존 Postgres 컨테이너
 * ({@link TestcontainersConfiguration})를 {@code @Import}로 재사용한다. Redis는 전용 모듈 없이
 * 코어 {@link GenericContainer}로 띄우고, 접속 정보는 {@code @DynamicPropertySource}로
 * {@code spring.data.redis.host/port}에 덮어쓴다.
 *
 * <p>이 테스트가 검증하는 것은 RedisConfig의 직렬화기 조합이다 — 키는 문자열, 값은 타입 힌트를 보존하는 JSON.
 * 임의의 값 하나가 아니라 타입이 다른 값(record·Map)이 각각 원형으로 복원되는지, raw 저장 형태가 JSON인지를 단언한다.
 */
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class RedisConfigTest {

    /** tech-stack.md §7: Redis 이미지는 redis:7-alpine으로 고정한다. */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** RedisConfig가 정의한 빈. 키=String, 값=JSON 직렬화기. */
    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    /** Boot가 기본 제공하는 문자열 전용 템플릿. 저장된 raw 바이트를 문자열로 읽어 저장 형태를 검사한다. */
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /** 왕복·타입 보존 검사용 단순 POJO. */
    record SamplePojo(String name, int age) {}

    @Test
    @DisplayName("record 값을 set/get 하면 타입이 보존된 채 동등한 값으로 왕복된다")
    void recordRoundTripsWithTypePreserved() {
        String key = "test:pojo";
        SamplePojo value = new SamplePojo("서울", 30);

        redisTemplate.opsForValue().set(key, value);
        Object read = redisTemplate.opsForValue().get(key);

        assertThat(read)
                .isInstanceOf(SamplePojo.class)
                .isEqualTo(value);
    }

    @Test
    @DisplayName("Map 값을 set/get 하면 엔트리가 보존된 채 왕복된다")
    void mapRoundTrips() {
        String key = "test:map";
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("district", "강남구");
        value.put("deposit", 50000L);

        redisTemplate.opsForValue().set(key, value);
        Object read = redisTemplate.opsForValue().get(key);

        assertThat(read)
                .isInstanceOf(Map.class)
                .isEqualTo(value);
    }

    @Test
    @DisplayName("키는 문자열 그대로, 값은 타입 힌트를 포함한 JSON 문자열로 저장된다")
    void valueIsStoredAsJsonWithTypeHint() {
        String key = "test:json";
        redisTemplate.opsForValue().set(key, new SamplePojo("서울", 30));

        // 값 직렬화기(GenericJacksonJsonRedisSerializer)와 무관하게, 키 직렬화기가 StringRedisSerializer이므로
        // StringRedisTemplate으로 같은 키를 그대로 조회할 수 있어야 한다(키가 raw 문자열로 저장됨).
        String raw = stringRedisTemplate.opsForValue().get(key);

        assertThat(raw)
                .as("값이 JSON 오브젝트로 저장되어야 한다")
                .isNotNull()
                .startsWith("{");
        assertThat(raw)
                .as("역직렬화용 타입 힌트로 구체 타입의 FQCN이 포함되어야 한다")
                .contains(SamplePojo.class.getName());
        assertThat(raw)
                .as("페이로드 필드가 포함되어야 한다")
                .contains("\"name\"")
                .contains("\"서울\"")
                .contains("\"age\"");
    }
}
