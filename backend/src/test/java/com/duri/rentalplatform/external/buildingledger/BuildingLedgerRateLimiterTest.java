package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link BuildingLedgerRateLimiter} 를 실제 Redis 에 대고 확인한다 — 초 안에서 상한까지 받기 · 다음 초까지 기다렸다 받기 · 기다림
 * 상한을 넘으면 포기 · 초 단위 키와 만료.
 *
 * <p>시계와 기다림은 테스트가 쥔다 — 기다림은 실제로 자지 않고 시계를 그만큼 옮긴다. 초는 실제 시각과 먼 과거(2001년)로 두어
 * 기동한 빈이 쓰는 지금 초의 키와 겹치지 않게 한다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildingLedgerRateLimiterTest {

    /** 2001-09-09T01:46:40.200Z — 초 1,000,000,000 의 200ms 지점. */
    private static final long START_MILLIS = 1_000_000_000_200L;
    private static final long START_SECOND = 1_000_000_000L;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    BuildingLedgerRateLimiter configuredLimiter;

    @Value("${external.building-ledger.per-second-limit}")
    long configuredLimit;

    private MutableClock clock;
    private List<Long> sleeps;

    @BeforeEach
    void setUp() {
        clear();
        clock = new MutableClock(START_MILLIS);
        sleeps = new ArrayList<>();
    }

    @AfterEach
    void clear() {
        for (long second = START_SECOND; second < START_SECOND + 5; second++) {
            stringRedisTemplate.delete(BuildingLedgerRateLimiter.keyOf(second));
        }
    }

    @Test
    @DisplayName("같은 초 안에서는 상한까지 기다리지 않고 받는다")
    void acquiresUpToLimitWithinSecond() {
        BuildingLedgerRateLimiter limiter = limiter(2, Duration.ofSeconds(3));

        assertThat(limiter.acquire()).isTrue();
        assertThat(limiter.acquire()).isTrue();

        assertThat(sleeps).isEmpty();
        assertThat(stringRedisTemplate.opsForValue().get(BuildingLedgerRateLimiter.keyOf(START_SECOND))).isEqualTo("2");
    }

    @Test
    @DisplayName("이번 초의 몫이 없으면 다음 초까지(남은 800ms) 기다렸다 다음 초의 키에서 받는다")
    void waitsUntilNextSecond() {
        BuildingLedgerRateLimiter limiter = limiter(1, Duration.ofSeconds(3));
        assertThat(limiter.acquire()).isTrue();

        assertThat(limiter.acquire()).isTrue();

        assertThat(sleeps).containsExactly(800L);
        assertThat(stringRedisTemplate.opsForValue().get(BuildingLedgerRateLimiter.keyOf(START_SECOND + 1)))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("기다림의 합이 상한을 넘으면 포기한다 — 넘는 기다림은 하지 않고, 거절한 시도는 세지 않는다")
    void givesUpBeyondMaxWait() {
        // 다른 슬롯이 이번 초와 다음 초의 몫을 다 쓴 상태.
        stringRedisTemplate.opsForValue().set(BuildingLedgerRateLimiter.keyOf(START_SECOND), "1");
        stringRedisTemplate.opsForValue().set(BuildingLedgerRateLimiter.keyOf(START_SECOND + 1), "1");
        BuildingLedgerRateLimiter limiter = limiter(1, Duration.ofMillis(1500));

        assertThat(limiter.acquire()).isFalse();

        // 800ms 를 기다려 다음 초에서도 못 받았고, 또 1,000ms 를 기다리면 상한 1,500ms 를 넘으므로 멈춘다.
        assertThat(sleeps).containsExactly(800L);
        assertThat(stringRedisTemplate.opsForValue().get(BuildingLedgerRateLimiter.keyOf(START_SECOND + 1)))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("기다림 상한 0 이면 기다리지 않고 바로 포기한다")
    void zeroMaxWaitNeverSleeps() {
        BuildingLedgerRateLimiter limiter = limiter(1, Duration.ZERO);
        assertThat(limiter.acquire()).isTrue();

        assertThat(limiter.acquire()).isFalse();
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("키는 external:building-ledger:per-second:{epochSecond} 이고 만료는 2초 이하로 걸린다")
    void keyIsEpochSecondWithTtl() {
        assertThat(limiter(5, Duration.ZERO).acquire()).isTrue();

        String key = "external:building-ledger:per-second:" + START_SECOND;
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("1");
        Long ttlSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("상한 1 미만 · 음수 기다림은 기동에서 막는다")
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> limiter(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기동한 빈은 설정 external.building-ledger.per-second-limit · per-second-max-wait 를 쓴다")
    void configuredBeanUsesProperties() {
        assertThat(ReflectionTestUtils.getField(configuredLimiter, "perSecondLimit")).isEqualTo(configuredLimit);
        assertThat(configuredLimit).isPositive();
        assertThat((long) ReflectionTestUtils.getField(configuredLimiter, "maxWaitMillis")).isPositive();
    }

    private BuildingLedgerRateLimiter limiter(long limit, Duration maxWait) {
        return new BuildingLedgerRateLimiter(stringRedisTemplate, limit, maxWait, clock, millis -> {
            sleeps.add(millis);
            clock.advance(millis);
        });
    }

    /** 테스트가 옮기는 시계. */
    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
