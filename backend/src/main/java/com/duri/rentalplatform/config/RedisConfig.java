package com.duri.rentalplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer valueSerializer =
                GenericJacksonJsonRedisSerializer.builder()
                        .enableDefaultTyping(polymorphicTypeValidator())
                        .build();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 값 직렬화기의 기본 타이핑(@class 타입 힌트 보존)을 안전하게 켜기 위한 검증기.
     *
     * <p>{@code enableUnsafeDefaultTyping()}(전면 허용) 대신 허용 타입을 명시한다. 역직렬화 시
     * 신뢰할 수 있는 타입만 인스턴스화되도록, 우리 도메인 패키지와 캐시·토큰 값에 흔한 JDK 타입만
     * base·subtype 양쪽으로 허용하고 그 외는 막는다. {@code allowIf...(String)}은 클래스 FQCN 접두사 매칭이다.
     *
     * <p>{@code java.lang.} 접두사 전체는 허용하지 않는다({@code Runtime}·{@code ProcessBuilder} 등
     * 역직렬화 가젯이 그 안에 있다). 대신 컬렉션 값으로 흔히 섞이는 스칼라({@link Number}·{@link String}·
     * {@link Boolean})만 subtype으로 열어 둔다 — 이들이 없으면 {@code Map<String,Object>}의 값 항목이
     * 타입 힌트를 검증에서 거부당한다.
     */
    private static PolymorphicTypeValidator polymorphicTypeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.duri.rentalplatform.")
                .allowIfSubType("com.duri.rentalplatform.")
                .allowIfBaseType("java.util.")
                .allowIfSubType("java.util.")
                .allowIfBaseType("java.time.")
                .allowIfSubType("java.time.")
                .allowIfSubType(Number.class)
                .allowIfSubType(String.class)
                .allowIfSubType(Boolean.class)
                .build();
    }
}
