package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * {@link BuildingLedgerDailyQuota} 를 실제 Redis 에 대고 확인한다 — 상한까지 받고 넘으면 거절 · 거절은 세지 않음 · 남은 수 ·
 * 서울 날짜 키와 만료.
 *
 * <p>상한은 작은 값으로 따로 만든 인스턴스로 본다 — 설정값(수천)까지 부르면 느리다. 시계는 고정한다. 날짜 경계를 가르려고 UTC
 * 15:30(서울 다음 날 00:30)을 쓴다. 기동한 빈이 설정 키를 읽는지는 한 번 따로 확인한다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuildingLedgerDailyQuotaTest {

    private static final long LIMIT = 3;

    /** UTC 2026-10-01 15:30 = 서울 2026-10-02 00:30. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T15:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEOUL_DATE = LocalDate.of(2026, 10, 2);

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    BuildingLedgerDailyQuota configuredQuota;

    @Value("${external.building-ledger.daily-limit}")
    long configuredLimit;

    private BuildingLedgerDailyQuota quota;

    @BeforeEach
    void setUp() {
        clear();
        quota = new BuildingLedgerDailyQuota(stringRedisTemplate, LIMIT, CLOCK);
    }

    @AfterEach
    void clear() {
        stringRedisTemplate.delete(BuildingLedgerDailyQuota.keyOf(SEOUL_DATE));
        stringRedisTemplate.delete(BuildingLedgerDailyQuota.keyOf(SEOUL_DATE.minusDays(1)));
    }

    @Test
    @DisplayName("상한까지 받고 그다음은 거절한다 — 거절한 시도는 세지 않는다")
    void acquiresUpToLimitThenRejects() {
        assertThat(quota.remaining()).isEqualTo(LIMIT);

        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.remaining()).isEqualTo(1);
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isFalse();
        assertThat(quota.tryAcquire()).isFalse();

        assertThat(quota.remaining()).isZero();
        assertThat(stringRedisTemplate.opsForValue().get(BuildingLedgerDailyQuota.keyOf(SEOUL_DATE)))
                .isEqualTo(String.valueOf(LIMIT));
    }

    @Test
    @DisplayName("키는 서울 날짜 external:building-ledger:daily-calls:{yyyy-MM-dd} 이고 만료는 2일 이하로 걸린다 — 전날 키와 섞이지 않는다")
    void keyIsSeoulDateWithTtl() {
        stringRedisTemplate.opsForValue().set(BuildingLedgerDailyQuota.keyOf(SEOUL_DATE.minusDays(1)), "3");

        assertThat(quota.tryAcquire()).isTrue();

        String key = "external:building-ledger:daily-calls:2026-10-02";
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("1");
        Long ttlSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
    }

    @Test
    @DisplayName("상한 0 이면 한 건도 받지 않는다 · 음수 상한은 기동에서 막는다")
    void zeroLimitRejectsAndNegativeIsInvalid() {
        BuildingLedgerDailyQuota closed = new BuildingLedgerDailyQuota(stringRedisTemplate, 0, CLOCK);

        assertThat(closed.tryAcquire()).isFalse();
        assertThat(closed.remaining()).isZero();
        assertThatThrownBy(() -> new BuildingLedgerDailyQuota(stringRedisTemplate, -1, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exhaust: 제공처 일일 한도 응답이면 오늘 카운터를 상한까지 채워 더 받지 않는다 — 만료도 걸린다")
    void exhaustFillsToLimit() {
        assertThat(quota.tryAcquire()).isTrue();

        quota.exhaust();

        assertThat(quota.remaining()).isZero();
        assertThat(quota.tryAcquire()).isFalse();
        String key = BuildingLedgerDailyQuota.keyOf(SEOUL_DATE);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo(String.valueOf(LIMIT));
        assertThat(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).isPositive()
                .isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
    }

    @Test
    @DisplayName("exhaust: 키가 없던 날에도 상한 값과 만료로 만든다 · 상한보다 큰 값은 줄이지 않는다")
    void exhaustCreatesKeyAndNeverLowers() {
        String key = BuildingLedgerDailyQuota.keyOf(SEOUL_DATE);

        quota.exhaust();
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo(String.valueOf(LIMIT));
        assertThat(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).isPositive();

        stringRedisTemplate.opsForValue().set(key, "10", Duration.ofHours(1));
        quota.exhaust();
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("10");
    }

    @Test
    @DisplayName("기동한 빈은 설정 external.building-ledger.daily-limit 을 상한으로 쓴다")
    void configuredBeanUsesProperty() {
        // 오늘(실제 서울 날짜) 키를 건드리지 않으려고 호출하지 않고 필드를 본다.
        assertThat(ReflectionTestUtils.getField(configuredQuota, "dailyLimit")).isEqualTo(configuredLimit);
        assertThat(configuredLimit).isPositive();
    }
}
