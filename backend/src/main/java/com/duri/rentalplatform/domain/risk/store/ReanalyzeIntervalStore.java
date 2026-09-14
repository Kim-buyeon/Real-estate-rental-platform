package com.duri.rentalplatform.domain.risk.store;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자 요청 재분석의 매물 단위 최소 간격. API 명세서(위험도 분석) 1.2.
 *
 * <p>분석이 <b>끝난 뒤</b> 간격만큼의 만료를 건 키를 둔다. 요청을 받은 시각에 두면 처리 중 들어온 같은 매물의 요청이 「진행 중인
 * 분석을 기다린다」 대신 429 가 된다. 키가 살아 있는 동안의 요청은 429 이고, 남은 만료 시간이 다음 요청 가능 시각이 된다.
 *
 * <p>두 인스턴스가 같은 간격을 봐야 하므로 Redis 에 둔다. 값은 쓰지 않고 키의 존재와 만료만 쓴다.
 */
@Component
public class ReanalyzeIntervalStore {

    private static final String KEY_PREFIX = "risk:reanalyze:interval:";

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration minInterval;

    public ReanalyzeIntervalStore(
            StringRedisTemplate stringRedisTemplate,
            @Value("${risk.reanalyze.min-interval}") Duration minInterval) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.minInterval = minInterval;
    }

    /** 재분석이 끝났다 — 지금부터 최소 간격 동안 같은 매물의 요청을 막는다. 이미 있으면 새로 건다. */
    public void markAnalyzed(Long propertyId) {
        stringRedisTemplate.opsForValue().set(keyOf(propertyId), "1", minInterval);
    }

    /**
     * 간격이 남아 있으면 남은 시간을 돌려준다.
     *
     * @return 남은 시간. 키가 없거나(-2) 만료가 없는(-1) 경우는 빈 값 — 만료 없는 키는 이 클래스가 만들지 않는다
     */
    public Optional<Duration> remaining(Long propertyId) {
        Long millis = stringRedisTemplate.getExpire(keyOf(propertyId), TimeUnit.MILLISECONDS);
        return millis == null || millis <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(millis));
    }

    static String keyOf(Long propertyId) {
        return KEY_PREFIX + propertyId;
    }
}
