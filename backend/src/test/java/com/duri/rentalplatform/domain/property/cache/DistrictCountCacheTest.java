package com.duri.rentalplatform.domain.property.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.github.benmanes.caffeine.cache.Ticker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DistrictCountCache} 검증 — 슬롯 로컬 적중 · 세대 · 같은 키 동시 빗나감 · 만료 · 크기 상한 · Redis 실패. Redis 층은 실제
 * {@link DistrictCountCacheStore} 에 목 Redis 를 끼우고, 시간은 테스트가 움직인다. 컨테이너를 쓰지 않는다.
 */
class DistrictCountCacheTest {

    private static final Duration REDIS_TTL = Duration.ofMinutes(10);
    private static final Duration LOCAL_TTL = Duration.ofMinutes(1);
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);
    private static final Instant START = Instant.parse("2026-10-05T03:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final DistrictCountRequest FILTER =
            new DistrictCountRequest(null, ContractType.DEPOSIT_ONLY, null, 300_000_000L, null, null, null, null, null);
    private static final DistrictCountRequest OTHER_FILTER =
            new DistrictCountRequest("강남구", null, null, null, null, null, null, null, null);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicLong elapsedNanos = new AtomicLong();
    private final Ticker ticker = elapsedNanos::get;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return START.plusNanos(elapsedNanos.get());
        }
    };

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DistrictCountCache cache;
    private AtomicInteger loads;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn("3");
        cache = newCache(500);
        loads = new AtomicInteger();
    }

    private DistrictCountCache newCache(long maxSize) {
        return new DistrictCountCache(new DistrictCountCacheStore(redis, jsonMapper, REDIS_TTL), LOCAL_TTL, maxSize,
                CHECK_INTERVAL, ticker, clock, Runnable::run);
    }

    /** 부를 때마다 세는 DB 집계 대역. 집계 시각은 지금(테스트 시계)이다. */
    private Supplier<DistrictCountsResponse> loader(long total) {
        return () -> {
            loads.incrementAndGet();
            return response(total, now());
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), SEOUL);
    }

    private static DistrictCountsResponse response(long total, OffsetDateTime aggregatedAt) {
        return new DistrictCountsResponse(
                List.of(new DistrictCountsResponse.District("강남구", total,
                        Map.of(RiskGrade.SAFE, total, RiskGrade.CAUTION, 0L, RiskGrade.DANGER, 0L))),
                total, aggregatedAt);
    }

    private static String redisKey(String generation, DistrictCountRequest filter) {
        return DistrictCountCacheStore.key(generation, filter);
    }

    private static DistrictCountRequest depositMax(long value) {
        return new DistrictCountRequest(null, null, null, value, null, null, null, null, null);
    }

    private void advance(Duration duration) {
        elapsedNanos.addAndGet(duration.toNanos());
    }

    // ---------- 로컬 적중 ----------

    @Test
    @DisplayName("로컬에 있으면 Redis 도 DB 도 부르지 않는다 — 두 번째 요청의 Redis 호출이 0 이다")
    void localHitCallsNeitherRedisNorDatabase() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));

        DistrictCountsResponse second = cache.getOrLoad(FILTER, loader(99));

        assertThat(second.totalCount()).isEqualTo(7);
        assertThat(loads).hasValue(1);
        verify(ops, times(1)).get(redisKey("3", FILTER));
        verify(ops, times(1)).get(DistrictCountCacheStore.GENERATION_KEY);
    }

    @Test
    @DisplayName("로컬이 비고 Redis 에 있으면 Redis 값을 돌려주고 로컬을 채운다 — DB 는 부르지 않는다")
    void redisHitFillsLocal() {
        cache.refreshGeneration();
        when(ops.get(redisKey("3", FILTER))).thenReturn(jsonMapper.writeValueAsString(response(5, now())));

        DistrictCountsResponse first = cache.getOrLoad(FILTER, loader(99));
        DistrictCountsResponse second = cache.getOrLoad(FILTER, loader(99));

        assertThat(first.totalCount()).isEqualTo(5);
        assertThat(second.totalCount()).isEqualTo(5);
        assertThat(loads).hasValue(0);
        verify(ops, times(1)).get(redisKey("3", FILTER));
    }

    @Test
    @DisplayName("둘 다 빗나가면 DB 집계를 Redis 에 세대가 든 키 · Redis TTL 로 쓴다")
    void missLoadsAndSavesUnderGenerationKey() {
        cache.refreshGeneration();

        cache.getOrLoad(FILTER, loader(7));

        verify(ops).set(eq(redisKey("3", FILTER)), anyString(), eq(REDIS_TTL));
        assertThat(redisKey("3", FILTER)).startsWith("property:district-counts:g3:");
    }

    @Test
    @DisplayName("필터 조합이 다르면 로컬 항목도 다르다")
    void differentFiltersAreDifferentEntries() {
        cache.refreshGeneration();

        cache.getOrLoad(FILTER, loader(7));
        DistrictCountsResponse other = cache.getOrLoad(OTHER_FILTER, loader(8));

        assertThat(other.totalCount()).isEqualTo(8);
        assertThat(loads).hasValue(2);
    }

    // ---------- 세대 ----------

    @Test
    @DisplayName("반복 작업이 바뀐 세대를 읽으면 로컬을 비우고 새 세대 키로 다시 찾는다")
    void generationChangeClearsLocal() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));

        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn("4");
        cache.refreshGeneration();
        DistrictCountsResponse after = cache.getOrLoad(FILTER, loader(8));

        assertThat(after.totalCount()).isEqualTo(8);
        assertThat(loads).hasValue(2);
        verify(ops).get(redisKey("4", FILTER));
    }

    @Test
    @DisplayName("세대가 같으면 반복 작업이 로컬을 비우지 않는다")
    void sameGenerationKeepsLocal() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));

        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(8));

        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("세대를 올리면 INCR 한 값을 세대로 쓰고 자기 슬롯의 로컬을 비운다")
    void bumpIncrementsAndClearsLocal() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(4L);

        cache.bumpGeneration();
        DistrictCountsResponse after = cache.getOrLoad(FILTER, loader(8));

        assertThat(after.totalCount()).isEqualTo(8);
        verify(ops).get(redisKey("4", FILTER));
    }

    @Test
    @DisplayName("세대를 올리다 Redis 가 실패해도 예외를 올리지 않고 로컬은 비운다")
    void bumpFailureOnlyWarnsAndStillClearsLocal() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(cache::bumpGeneration).doesNotThrowAnyException();
        cache.getOrLoad(FILTER, loader(8));

        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("세대 키가 없으면 세대 0 으로 쓴다")
    void missingGenerationKeyMeansInitial() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(null);

        cache.getOrLoad(FILTER, loader(7));

        verify(ops).get(redisKey(DistrictCountCacheStore.INITIAL_GENERATION, FILTER));
    }

    @Test
    @DisplayName("기동 직후 세대를 모르면 첫 요청이 한 번만 읽고, 이후 요청은 세대를 읽지 않는다")
    void firstRequestReadsGenerationOnce() {
        cache.getOrLoad(FILTER, loader(7));
        cache.getOrLoad(OTHER_FILTER, loader(8));
        cache.getOrLoad(FILTER, loader(9));

        verify(ops, times(1)).get(DistrictCountCacheStore.GENERATION_KEY);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("세대를 모르는데 Redis 가 실패하면 담지 않고 DB 로 간다 — 조회는 실패하지 않는다")
    void unknownGenerationWithRedisDownGoesToDatabase() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new QueryTimeoutException("redis"));

        DistrictCountsResponse first = cache.getOrLoad(FILTER, loader(7));
        DistrictCountsResponse second = cache.getOrLoad(FILTER, loader(8));

        assertThat(first.totalCount()).isEqualTo(7);
        assertThat(second.totalCount()).isEqualTo(8);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("세대를 못 읽었으면 요청 경로는 확인 간격에 한 번만 다시 읽고, 그 사이 요청은 Redis 없이 DB 로 간다")
    void requestPathRetriesGenerationReadOncePerInterval() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new QueryTimeoutException("redis"));

        cache.getOrLoad(FILTER, loader(7));
        cache.getOrLoad(FILTER, loader(7));
        cache.getOrLoad(FILTER, loader(7));
        verify(ops, times(1)).get(DistrictCountCacheStore.GENERATION_KEY);

        advance(CHECK_INTERVAL);
        cache.getOrLoad(FILTER, loader(7));
        verify(ops, times(2)).get(DistrictCountCacheStore.GENERATION_KEY);
        assertThat(loads).hasValue(4);
    }

    @Test
    @DisplayName("다른 쪽이 세대를 읽는 중이면 요청은 기다리지 않고 캐시 없이 DB 로 간다")
    void requestDoesNotWaitForInFlightGenerationRead() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenAnswer(invocation -> {
            readEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "3";
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slowRead = pool.submit(cache::refreshGeneration);
            assertThat(readEntered.await(5, TimeUnit.SECONDS)).isTrue();

            DistrictCountsResponse response = cache.getOrLoad(FILTER, loader(7));

            assertThat(response.totalCount()).isEqualTo(7);
            assertThat(loads).hasValue(1);
            verify(ops, never()).get(redisKey("3", FILTER));
            release.countDown();
            slowRead.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(ops, times(1)).get(DistrictCountCacheStore.GENERATION_KEY);
    }

    @Test
    @DisplayName("반복 작업의 세대 읽기가 실패하면 지금 세대와 로컬을 그대로 둔다")
    void generationReadFailureKeepsLocal() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new QueryTimeoutException("redis"));

        assertThatCode(cache::refreshGeneration).doesNotThrowAnyException();
        cache.getOrLoad(FILTER, loader(8));

        assertThat(loads).hasValue(1);
    }

    // ---------- Redis 실패 ----------

    @Test
    @DisplayName("Redis 캐시 읽기 · 쓰기가 실패해도 DB 집계를 돌려주고 로컬에는 담는다")
    void redisCacheFailureFallsBackToDatabase() {
        cache.refreshGeneration();
        when(ops.get(startsWith("property:district-counts:g"))).thenThrow(new QueryTimeoutException("redis"));
        doThrow(new QueryTimeoutException("redis")).when(ops).set(anyString(), anyString(), any(Duration.class));

        DistrictCountsResponse first = cache.getOrLoad(FILTER, loader(7));
        DistrictCountsResponse second = cache.getOrLoad(FILTER, loader(8));

        assertThat(first.totalCount()).isEqualTo(7);
        assertThat(second.totalCount()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    // ---------- 같은 키 동시 빗나감 ----------

    @Test
    @DisplayName("같은 키의 동시 빗나감은 슬롯 안에서 한 번만 Redis · DB 로 가고 나머지는 그 결과를 받는다")
    void concurrentMissesLoadOnce() throws Exception {
        cache.refreshGeneration();
        int callers = 8;
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<DistrictCountsResponse> slowLoader = () -> {
            loads.incrementAndGet();
            loaderEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response(7, now());
        };

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            Future<DistrictCountsResponse> owner = pool.submit(() -> cache.getOrLoad(FILTER, slowLoader));
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<DistrictCountsResponse>> waiters = new ArrayList<>();
            for (int i = 1; i < callers; i++) {
                waiters.add(pool.submit(() -> cache.getOrLoad(FILTER, slowLoader)));
            }
            release.countDown();

            assertThat(owner.get(5, TimeUnit.SECONDS).totalCount()).isEqualTo(7);
            for (Future<DistrictCountsResponse> waiter : waiters) {
                assertThat(waiter.get(5, TimeUnit.SECONDS).totalCount()).isEqualTo(7);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
        verify(ops, times(1)).get(redisKey("3", FILTER));
    }

    @Test
    @DisplayName("DB 집계가 실패하면 예외를 그대로 올리고 담지 않는다 — 다음 요청이 다시 읽는다")
    void loaderFailurePropagatesAndIsNotCached() {
        cache.refreshGeneration();
        IllegalStateException failure = new IllegalStateException("db");

        assertThatThrownBy(() -> cache.getOrLoad(FILTER, () -> {
            throw failure;
        })).isSameAs(failure);
        DistrictCountsResponse retried = cache.getOrLoad(FILTER, loader(7));

        assertThat(retried.totalCount()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    // ---------- 만료 · 크기 ----------

    @Test
    @DisplayName("로컬 만료(1분)가 지나면 Redis 를 다시 읽는다")
    void localEntryExpiresAfterLocalTtl() {
        cache.refreshGeneration();
        cache.getOrLoad(FILTER, loader(7));

        advance(LOCAL_TTL.minusSeconds(1));
        cache.getOrLoad(FILTER, loader(8));
        verify(ops, times(1)).get(redisKey("3", FILTER));

        advance(Duration.ofSeconds(2));
        cache.getOrLoad(FILTER, loader(8));
        verify(ops, times(2)).get(redisKey("3", FILTER));
    }

    @Test
    @DisplayName("Redis 에서 묵은 값은 「집계 시각 + Redis TTL」 까지만 로컬에 둔다 — 응답이 낡을 수 있는 상한이 Redis TTL 이다")
    void localLifetimeCappedByAggregatedAtPlusRedisTtl() {
        cache.refreshGeneration();
        OffsetDateTime aggregatedNineAndHalfMinutesAgo = now().minus(Duration.ofSeconds(9 * 60 + 30));
        when(ops.get(redisKey("3", FILTER)))
                .thenReturn(jsonMapper.writeValueAsString(response(5, aggregatedNineAndHalfMinutesAgo)));
        cache.getOrLoad(FILTER, loader(99));

        advance(Duration.ofSeconds(29));
        cache.getOrLoad(FILTER, loader(99));
        verify(ops, times(1)).get(redisKey("3", FILTER));

        advance(Duration.ofSeconds(2));
        cache.getOrLoad(FILTER, loader(99));
        verify(ops, times(2)).get(redisKey("3", FILTER));
    }

    @Test
    @DisplayName("크기 상한을 넘으면 로컬 항목을 내보낸다")
    void localSizeIsBounded() {
        DistrictCountCache tiny = newCache(1);
        tiny.refreshGeneration();
        for (int i = 0; i < 50; i++) {
            tiny.getOrLoad(depositMax(i), loader(i));
        }
        loads.set(0);

        for (int i = 0; i < 50; i++) {
            tiny.getOrLoad(depositMax(i), loader(i));
        }

        // 상한 1건이라 거의 전부 다시 읽는다. 정리는 같은 스레드에서 돌지만 W-TinyLFU 가 무엇을 남길지는 보지 않는다.
        assertThat(loads.get()).isGreaterThanOrEqualTo(48);
    }

    @Test
    @DisplayName("로컬 만료가 Redis TTL 보다 짧지 않으면 빈을 만들지 못한다")
    void localTtlMustBeShorterThanRedisTtl() {
        assertThatThrownBy(() -> new DistrictCountCache(new DistrictCountCacheStore(redis, jsonMapper, REDIS_TTL),
                REDIS_TTL, 500, CHECK_INTERVAL, ticker, clock, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 키 정규화 ----------

    @Test
    @DisplayName("등급 배열의 순서 · 중복 · 면적의 끝자리 0 · 자치구 앞뒤 공백이 달라도 같은 필터 키다")
    void filterKeyIsNormalized() {
        DistrictCountRequest a = new DistrictCountRequest(" 강남구 ", null, null, null, null, null,
                List.of(RiskGrade.DANGER, RiskGrade.SAFE, RiskGrade.SAFE), new BigDecimal("40.0"), null);
        DistrictCountRequest b = new DistrictCountRequest("강남구", null, null, null, null, null,
                List.of(RiskGrade.SAFE, RiskGrade.DANGER), new BigDecimal("40"), null);

        assertThat(DistrictCountCacheStore.filterKey(a)).isEqualTo(DistrictCountCacheStore.filterKey(b));
    }
}
