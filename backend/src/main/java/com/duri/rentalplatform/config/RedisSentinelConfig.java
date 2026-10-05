package com.duri.rentalplatform.config;

import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redis Sentinel 접속(INF-01 #404)의 「설정하지 않음」을 빈 값까지 넓힌다.
 *
 * <p><b>왜 필요한가</b> — Boot 는 {@code spring.data.redis.sentinel.*} 중 무엇 하나라도 바인딩되면 Sentinel 모드로 간다
 * (spring-boot-data-redis 4.1.1 {@code DataRedisConnectionConfiguration.determineMode} — {@code getSentinelConfig() != null}).
 * 빈 문자열도 바인딩으로 친다. 운영 Compose 는 {@code .env} 를 env_file 로 넘기므로 {@code SPRING_DATA_REDIS_SENTINEL_MASTER=}
 * 처럼 값 없는 줄이 빈 환경 변수가 되고, application.yml 의 {@code ${...:}} 기본값도 빈 문자열이다. 그대로 두면
 * Sentinel 을 쓰지 않는 구성(로컬 · CI · Sentinel 전 운영)이 빈 master 로 Sentinel 접속을 만들다 기동에 실패한다.
 * 여기서 master · nodes 가 둘 다 비었으면 Sentinel 설정을 지워 단일 노드(host · port)로 돌린다.
 *
 * <p>한쪽만 있으면 설정 실수다 — 조용히 단일 노드로 떨어지면 두 앱 노드가 서로 다른 Redis 를 쓰게 되므로 기동을 멈춘다.
 *
 * <p><b>TLS 는 여기서 다루지 않는다</b> — {@code spring.data.redis.ssl.enabled} 와 번들이 있으면 Boot 가 클라이언트 설정에 SSL 을
 * 켜고, Spring Data Redis 가 그 값을 주 Redis 와 모든 Sentinel 접속에 똑같이 적용한다. 근거는 이 클래스의 테스트가 확인한다.
 */
@Configuration
public class RedisSentinelConfig {

    /** static — 후처리기는 다른 빈보다 먼저 만들어져야 하고, 설정 클래스 인스턴스를 일찍 끌어오지 않는다. */
    @Bean
    static BeanPostProcessor blankRedisSentinelRemover() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataRedisProperties properties) {
                    clearIfBlank(properties);
                }
                return bean;
            }
        };
    }

    /**
     * master · nodes 가 둘 다 비었으면 Sentinel 설정을 지운다. 바인딩이 끝난 뒤(초기화 후) 부른다 — 바인딩은 초기화 전 후처리에서 일어난다.
     *
     * @throws IllegalStateException master 와 nodes 중 한쪽만 있을 때
     */
    static void clearIfBlank(DataRedisProperties properties) {
        DataRedisProperties.Sentinel sentinel = properties.getSentinel();
        if (sentinel == null) {
            return;
        }
        boolean hasMaster = StringUtils.hasText(sentinel.getMaster());
        boolean hasNodes = hasAnyNode(sentinel.getNodes());
        if (!hasMaster && !hasNodes) {
            properties.setSentinel(null);
            return;
        }
        if (!hasMaster || !hasNodes) {
            throw new IllegalStateException("Redis Sentinel 설정이 반쪽이다 — spring.data.redis.sentinel.master 와 "
                    + "spring.data.redis.sentinel.nodes 는 함께 두거나 함께 비운다 (master=" + sentinel.getMaster()
                    + ", nodes=" + sentinel.getNodes() + ")");
        }
    }

    private static boolean hasAnyNode(List<String> nodes) {
        return nodes != null && nodes.stream().anyMatch(StringUtils::hasText);
    }
}
