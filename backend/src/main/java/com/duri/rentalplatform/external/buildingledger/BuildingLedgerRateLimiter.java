package com.duri.rentalplatform.external.buildingledger;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 건축HUB 건축물대장정보 호출의 초당 상한. 설정 {@code external.building-ledger.per-second-limit} ·
 * {@code external.building-ledger.per-second-max-wait}.
 *
 * <p><b>왜 있는가</b> — 제공처는 초당 요청 수가 넘으면 {@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR} 로
 * 거절한다. 앱 슬롯 넷이 같은 인증키를 쓰므로 한 프로세스 안의 제한으로는 막지 못한다 — 카운터를 Redis 에 두고 나눠 쓴다.
 *
 * <p><b>무엇을 세는가</b> — {@link BuildingLedgerDailyQuota} 와 같다. 요청(표제부 한 페이지) 한 건마다 하나이고
 * {@link RealBuildingLedgerClient} 가 요청 직전에 {@link #acquire()} 로 받는다.
 *
 * <p><b>키</b> — 초 단위 {@code external:building-ledger:per-second:{epochSecond}}. 초가 넘어가면 새 키이고, 만료
 * {@value #KEY_TTL_SECONDS}초로 지난 키가 쌓이지 않게 한다. 초는 앱 시계로 정한다 — 슬롯끼리 시계가 어긋난 만큼 같은 초의 몫이
 * 두 키로 갈라질 수 있다(상한보다 조금 더 나갈 수 있다). 제공처가 거절해도 {@link BuildingLedgerRateLimitedException} 로 처리되므로
 * 장애로 번지지 않는다.
 *
 * <p><b>기다림</b> — 이번 초의 몫이 없으면 다음 초까지 기다렸다 다시 받는다. 기다림의 합이 상한을 넘으면 포기한다 — 사용자 조회
 * 스레드가 오래 묶이지 않게 한다. 원자성 · 문자열 템플릿을 쓰는 이유는 {@link BuildingLedgerDailyQuota} 와 같다.
 */
@Component
public class BuildingLedgerRateLimiter {

    static final String KEY_PREFIX = "external:building-ledger:per-second:";
    static final long KEY_TTL_SECONDS = 2;

    /** 1 이면 한 칸을 받았다, 0 이면 이번 초의 상한에 닿았다. 닿은 뒤의 시도는 세지 않는다. */
    private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('get', KEYS[1]) or '0')
            if current >= tonumber(ARGV[1]) then
              return 0
            end
            if redis.call('incr', KEYS[1]) == 1 then
              redis.call('expire', KEYS[1], ARGV[2])
            end
            return 1
            """, Long.class);

    /** 기다리는 방법. 테스트가 실제로 자지 않게 바꿔 끼운다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final long perSecondLimit;
    private final long maxWaitMillis;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public BuildingLedgerRateLimiter(
            StringRedisTemplate stringRedisTemplate,
            @Value("${external.building-ledger.per-second-limit}") long perSecondLimit,
            @Value("${external.building-ledger.per-second-max-wait}") Duration maxWait) {
        this(stringRedisTemplate, perSecondLimit, maxWait, Clock.systemUTC(), Thread::sleep);
    }

    BuildingLedgerRateLimiter(StringRedisTemplate stringRedisTemplate, long perSecondLimit, Duration maxWait,
            Clock clock, Sleeper sleeper) {
        if (perSecondLimit < 1) {
            throw new IllegalArgumentException(
                    "external.building-ledger.per-second-limit 은 1 이상이어야 한다: " + perSecondLimit);
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("external.building-ledger.per-second-max-wait 은 0 이상이어야 한다: " + maxWait);
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.perSecondLimit = perSecondLimit;
        this.maxWaitMillis = maxWait.toMillis();
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * 요청 한 건을 보낼 몫을 받는다. 이번 초의 몫이 없으면 다음 초까지 기다렸다 다시 받는다.
     *
     * @return 받았으면 참. 기다림의 합이 상한을 넘었거나 기다리는 중에 인터럽트되면 거짓
     */
    public boolean acquire() {
        long waited = 0;
        while (true) {
            long nowMillis = clock.millis();
            if (tryAcquire(Math.floorDiv(nowMillis, 1000L))) {
                return true;
            }
            long untilNextSecond = 1000L - Math.floorMod(nowMillis, 1000L);
            if (waited + untilNextSecond > maxWaitMillis) {
                return false;
            }
            try {
                sleeper.sleep(untilNextSecond);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += untilNextSecond;
        }
    }

    private boolean tryAcquire(long epochSecond) {
        Long acquired = stringRedisTemplate.execute(ACQUIRE_SCRIPT, List.of(keyOf(epochSecond)),
                String.valueOf(perSecondLimit), String.valueOf(KEY_TTL_SECONDS));
        return acquired != null && acquired == 1L;
    }

    static String keyOf(long epochSecond) {
        return KEY_PREFIX + epochSecond;
    }
}
