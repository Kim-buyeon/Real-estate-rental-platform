package com.duri.rentalplatform.domain.property.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.store.MapClusterCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.MapClusterCacheEntry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MapClusterCache} 검증 — 적중 · 키 · 세대 공유 · 만료 · 무게 상한 · 같은 키 동시 빗나감 · 실패 · Redis 층(로컬 → Redis →
 * DB 순서 · 수명 상한). 세대는 실제 {@link DistrictCountCache} 에 목 Redis 를 끼우고, Redis 층은 {@link MapClusterCacheStore}
 * 목으로 확인한다(실패 흡수는 예외를 던지는 목 Redis 를 실제 보관소에 끼워 확인). 시간은 테스트가 움직인다. 컨테이너를 쓰지 않는다.
 */
class MapClusterCacheTest {

    private static final Duration LOCAL_TTL = Duration.ofMinutes(1);
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);
    private static final int GRID = 12;

    private static final DistrictCountRequest FILTER =
            new DistrictCountRequest("강서구", ContractType.DEPOSIT_ONLY, null, null, null, null, null, null, null);
    private static final BoundingBox BOX = new BoundingBox(37.52, 37.58, 126.81, 126.89);

    private final AtomicLong elapsedNanos = new AtomicLong();
    private final Ticker ticker = elapsedNanos::get;
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final Duration REDIS_TTL = Duration.ofMinutes(1);
    /** 티커와 함께 움직이는 시계 — 항목 수명 계산이 보는 「지금」. */
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

    private ValueOperations<String, String> ops;
    private MapClusterCacheStore store;
    private DistrictCountCache generationSource;
    private MapClusterCache cache;
    private AtomicInteger loads;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn("3");
        generationSource = new DistrictCountCache(
                new DistrictCountCacheStore(redis, JsonMapper.builder().build(), Duration.ofMinutes(10)),
                Duration.ofMinutes(1), 500, CHECK_INTERVAL, ticker, Clock.systemUTC(), Runnable::run);
        store = mock(MapClusterCacheStore.class);
        when(store.ttl()).thenReturn(REDIS_TTL);
        when(store.find(any(), any())).thenReturn(Optional.empty());
        cache = newCache(20_000);
        loads = new AtomicInteger();
    }

    private MapClusterCache newCache(long maxWeight) {
        return newCache(store, maxWeight);
    }

    private MapClusterCache newCache(MapClusterCacheStore redisLayer, long maxWeight) {
        return new MapClusterCache(generationSource, redisLayer, LOCAL_TTL, maxWeight, ticker, clock, Runnable::run);
    }

    private static MapClusterCacheEntry entry(Instant savedAt, long total) {
        return new MapClusterCacheEntry(savedAt, PropertyMapClustersResponse.unclustered(total, List.of()));
    }

    /** 부를 때마다 세는 DB 조회 대역. total 로 어느 읽기의 응답인지 가린다. */
    private Supplier<PropertyMapClustersResponse> loader(long total) {
        return () -> {
            loads.incrementAndGet();
            return PropertyMapClustersResponse.unclustered(total, List.of());
        };
    }

    private static PropertyMapClustersResponse withMarkers(int count) {
        List<PropertyMarkerResponse> markers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            markers.add(new PropertyMarkerResponse((long) i, BigDecimal.ONE, BigDecimal.ONE, 1L, null,
                    ContractType.DEPOSIT_ONLY, 0L, "강서구", null, null));
        }
        return PropertyMapClustersResponse.unclustered(count, markers);
    }

    private void advance(Duration duration) {
        elapsedNanos.addAndGet(duration.toNanos());
    }

    // ---------- 적중 · 키 ----------

    @Test
    @DisplayName("같은 필터 · 영역 · 행 · 열이면 두 번째 요청은 DB 를 부르지 않는다")
    void sameKeyHitsLocal() {
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        PropertyMapClustersResponse second = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        assertThat(second.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("영역 · 필터 · 행 수 · 열 수 중 하나라도 다르면 다른 항목이다")
    void anyDifferenceIsDifferentEntry() {
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(1));

        cache.getOrLoad(FILTER, new BoundingBox(37.52, 37.58, 126.81, 126.8901), GRID, GRID, loader(2));
        cache.getOrLoad(new DistrictCountRequest("강서구", null, null, null, null, null, null, null, null),
                BOX, GRID, GRID, loader(3));
        cache.getOrLoad(FILTER, BOX, GRID + 1, GRID, loader(4));
        cache.getOrLoad(FILTER, BOX, GRID, GRID + 1, loader(5));

        assertThat(loads).hasValue(5);
    }

    @Test
    @DisplayName("같은 double 이면 같은 키다 — 요청 문자열의 끝자리 0 · -0.0 은 키를 바꾸지 않는다")
    void coordinateKeyIsNormalized() {
        BoundingBox written = new BoundingBox(Double.parseDouble("37.5200"), Double.parseDouble("37.580"),
                Double.parseDouble("126.81"), Double.parseDouble("126.890"));
        assertThat(MapClusterCache.key(FILTER, written, GRID, GRID)).isEqualTo(MapClusterCache.key(FILTER, BOX, GRID, GRID));

        BoundingBox negativeZero = new BoundingBox(-0.0, 0.0, -0.0, 0.0);
        BoundingBox zero = new BoundingBox(0.0, 0.0, 0.0, 0.0);
        assertThat(MapClusterCache.key(FILTER, negativeZero, GRID, GRID)).isEqualTo(MapClusterCache.key(FILTER, zero, GRID, GRID));
    }

    @Test
    @DisplayName("키는 필터 키 · 영역 네 값 · 행 · 열 수를 담는다")
    void keyContents() {
        assertThat(MapClusterCache.key(FILTER, BOX, 18, 15))
                .isEqualTo(DistrictCountCacheStore.filterKey(FILTER) + "|box=37.52,37.58,126.81,126.89|rows=18,cols=15");
    }

    @Test
    @DisplayName("행 · 열이 같으면 같은 키, 행과 열을 바꾸면 다른 키다")
    void rowsAndColsAreSeparateKeyParts() {
        assertThat(MapClusterCache.key(FILTER, BOX, 18, 15)).isEqualTo(MapClusterCache.key(FILTER, BOX, 18, 15));
        assertThat(MapClusterCache.key(FILTER, BOX, 18, 15)).isNotEqualTo(MapClusterCache.key(FILTER, BOX, 15, 18));
        assertThat(MapClusterCache.key(FILTER, BOX, 12, 12)).isNotEqualTo(MapClusterCache.key(FILTER, BOX, 12, 13));
    }

    // ---------- 세대 ----------

    @Test
    @DisplayName("반복 작업이 바뀐 세대를 읽으면 지도 묶음 캐시도 비운다")
    void generationChangeClearsMapClusters() {
        generationSource.refreshGeneration();
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn("4");
        generationSource.refreshGeneration();
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(after.total()).isEqualTo(8);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("배치 끝에 세대를 올리면 지도 묶음 캐시도 비운다")
    void bumpClearsMapClusters() {
        generationSource.refreshGeneration();
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(4L);

        generationSource.bumpGeneration();
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(after.total()).isEqualTo(8);
    }

    @Test
    @DisplayName("옛 세대로 시작한 읽기가 비우기 뒤에 끝나 담겨도 새 세대 요청은 그것을 읽지 않는다")
    void staleLoadFinishingAfterBumpIsNotServedToNewGeneration() {
        generationSource.refreshGeneration();
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(4L);

        // 읽기 도중에 배치가 끝나 세대가 오르고 캐시가 비워진다 — 읽기는 그 뒤에 끝나 옛 세대 키로 담긴다.
        PropertyMapClustersResponse stale = cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            loads.incrementAndGet();
            generationSource.bumpGeneration();
            return PropertyMapClustersResponse.unclustered(7, List.of());
        });
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(stale.total()).isEqualTo(7);
        assertThat(after.total()).isEqualTo(8);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("세대를 읽지 못하면 담지 않고 매번 DB 로 간다")
    void unknownGenerationBypassesCache() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));
        advance(CHECK_INTERVAL);
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(loads).hasValue(2);
    }

    // ---------- 만료 · 크기 ----------

    @Test
    @DisplayName("만료가 지나면 DB 에서 다시 읽는다")
    void expiresAfterLocalTtl() {
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        advance(LOCAL_TTL.minusSeconds(1));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));
        assertThat(loads).hasValue(1);

        advance(Duration.ofSeconds(1));
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(9));
        assertThat(after.total()).isEqualTo(9);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("무게는 1 + 묶음 수 + 마커 수다")
    void weightCountsItems() {
        assertThat(MapClusterCache.weight(PropertyMapClustersResponse.unclustered(0, List.of()))).isEqualTo(1);
        assertThat(MapClusterCache.weight(withMarkers(40))).isEqualTo(41);
    }

    @Test
    @DisplayName("무게 합이 상한을 넘으면 항목을 내보낸다")
    void evictsOverMaxWeight() {
        cache = newCache(100);
        BoundingBox otherBox = new BoundingBox(37.40, 37.46, 126.81, 126.89);
        cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });
        cache.getOrLoad(FILTER, otherBox, GRID, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });

        cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });
        cache.getOrLoad(FILTER, otherBox, GRID, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });

        // 61 + 61 > 100 — 둘이 함께 남을 수 없어 네 번 중 적어도 세 번은 DB 로 간다.
        assertThat(loads.get()).isGreaterThanOrEqualTo(3);
    }

    // ---------- 동시 빗나감 · 실패 ----------

    @Test
    @DisplayName("같은 키의 동시 빗나감은 하나만 DB 로 가고 나머지는 그 결과를 받는다")
    void concurrentMissesLoadOnce() throws Exception {
        int threads = 8;
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<PropertyMapClustersResponse> first = pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return PropertyMapClustersResponse.unclustered(7, List.of());
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<PropertyMapClustersResponse>> waiters = new ArrayList<>();
            for (int i = 1; i < threads; i++) {
                waiters.add(pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99))));
            }
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).total()).isEqualTo(7);
            for (Future<PropertyMapClustersResponse> waiter : waiters) {
                assertThat(waiter.get(5, TimeUnit.SECONDS).total()).isEqualTo(7);
            }
            assertThat(loads).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("읽기가 실패하면 예외가 그대로 올라가고 담지 않는다 — 다음 요청이 다시 읽는다")
    void failureIsNotCached() {
        assertThatThrownBy(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);

        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        assertThat(after.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("읽기는 부른 스레드에서 한다 — 읽기 트랜잭션 · 읽기 분산 표시가 스레드에 묶여 있다")
    void loadsOnCallingThread() {
        Thread caller = Thread.currentThread();
        List<Thread> loadThread = new ArrayList<>();

        cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            loadThread.add(Thread.currentThread());
            return PropertyMapClustersResponse.unclustered(1, List.of());
        });

        assertThat(loadThread).containsExactly(caller);
    }

    // ---------- Redis 층 ----------

    @Test
    @DisplayName("로컬이 빗나가고 Redis 가 적중하면 DB 를 부르지 않고 Redis 에 쓰지도 않는다")
    void redisHitSkipsLoaderAndSave() {
        when(store.find("3", MapClusterCache.key(FILTER, BOX, GRID, GRID))).thenReturn(Optional.of(entry(START, 5)));

        PropertyMapClustersResponse response = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        assertThat(response.total()).isEqualTo(5);
        assertThat(loads).hasValue(0);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("Redis 에서 찾은 값은 로컬에 담겨 다음 요청은 Redis 도 DB 도 부르지 않는다")
    void redisHitIsKeptLocally() {
        when(store.find(eq("3"), any())).thenReturn(Optional.of(entry(START, 5)));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        PropertyMapClustersResponse second = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        assertThat(second.total()).isEqualTo(5);
        assertThat(loads).hasValue(0);
        verify(store, times(1)).find(eq("3"), any());
    }

    @Test
    @DisplayName("로컬 적중이면 Redis 를 다시 부르지 않는다")
    void localHitSkipsRedis() {
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        verify(store, times(1)).find(any(), any());
        verify(store, times(1)).save(any(), any(), any());
    }

    @Test
    @DisplayName("Redis 도 빗나가면 DB 에서 읽고, 세대 · 캐시 키 · 읽은 시각과 함께 Redis 에 쓴다")
    void dbLoadIsSavedToRedis() {
        PropertyMapClustersResponse response = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        ArgumentCaptor<MapClusterCacheEntry> saved = ArgumentCaptor.forClass(MapClusterCacheEntry.class);
        verify(store).save(eq("3"), eq(MapClusterCache.key(FILTER, BOX, GRID, GRID)), saved.capture());
        assertThat(saved.getValue().response()).isEqualTo(response);
        assertThat(saved.getValue().savedAt()).isEqualTo(START);
        assertThat(response.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("Redis 읽기가 빈 값을 주면 DB 로 가서 정상 응답한다")
    void redisFindEmptyFallsBackToDb() {
        PropertyMapClustersResponse response = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        assertThat(response.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("Redis 읽기 · 쓰기가 예외를 던져도 DB 에서 읽어 정상 응답하고 로컬에는 담긴다")
    @SuppressWarnings("unchecked")
    void redisExceptionsAreAbsorbed() {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        ValueOperations<String, String> failingOps = mock(ValueOperations.class);
        when(failing.opsForValue()).thenReturn(failingOps);
        when(failingOps.get(any())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down")).when(failingOps).set(any(), any(), any(Duration.class));
        cache = newCache(new MapClusterCacheStore(failing, JsonMapper.builder().build(), REDIS_TTL), 20_000);

        PropertyMapClustersResponse first = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));
        PropertyMapClustersResponse second = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(first.total()).isEqualTo(7);
        assertThat(second.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
        verify(failingOps).get(MapClusterCacheStore.key("3", MapClusterCache.key(FILTER, BOX, GRID, GRID)));
    }

    @Test
    @DisplayName("Redis 에서 묵은 값을 가져오면 로컬 수명이 savedAt + Redis TTL 까지로 깎인다")
    void localLifetimeIsCappedBySavedAtPlusRedisTtl() {
        // 50초 전에 DB 에서 읽힌 값 — Redis TTL 1분까지 10초 남았다(로컬 만료 1분보다 짧다).
        when(store.find(eq("3"), any())).thenReturn(Optional.of(entry(START.minusSeconds(50), 5)));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        advance(Duration.ofSeconds(9));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));
        verify(store, times(1)).find(any(), any());

        advance(Duration.ofSeconds(1));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));
        verify(store, times(2)).find(any(), any());
    }

    @Test
    @DisplayName("수명이 깎여 만료된 뒤 Redis 도 비었으면 DB 로 간다")
    void afterCappedLifetimeFallsThroughToDb() {
        when(store.find(eq("3"), any())).thenReturn(Optional.of(entry(START.minusSeconds(50), 5)));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        advance(Duration.ofSeconds(10));
        when(store.find(eq("3"), any())).thenReturn(Optional.empty());
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));

        assertThat(after.total()).isEqualTo(8);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("Redis TTL 이 이미 지난 savedAt 의 값은 로컬에 남지 않는다")
    void expiredSavedAtIsNotKeptLocally() {
        when(store.find(eq("3"), any())).thenReturn(Optional.of(entry(START.minus(REDIS_TTL).minusSeconds(1), 5)));

        PropertyMapClustersResponse first = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99));

        assertThat(first.total()).isEqualTo(5);
        verify(store, times(2)).find(any(), any());
    }

    @Test
    @DisplayName("방금 DB 에서 읽은 값의 로컬 수명은 로컬 만료다 — Redis TTL 이 더 길어도 로컬 만료를 넘지 않는다")
    void freshValueLivesForLocalTtl() {
        when(store.ttl()).thenReturn(Duration.ofMinutes(10));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        advance(LOCAL_TTL.minusSeconds(1));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(8));
        verify(store, times(1)).find(any(), any());

        advance(Duration.ofSeconds(1));
        cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(9));
        verify(store, times(2)).find(any(), any());
    }

    @Test
    @DisplayName("세대를 모르면 Redis 도 부르지 않고 DB 로 간다")
    void unknownGenerationSkipsRedis() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        PropertyMapClustersResponse response = cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(7));

        assertThat(response.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
        verify(store, never()).find(any(), any());
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("같은 키의 동시 빗나감은 Redis 읽기 · DB 읽기 · Redis 쓰기를 각 한 번만 한다")
    void concurrentMissesHitRedisAndDbOnce() throws Exception {
        int threads = 8;
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<PropertyMapClustersResponse> first = pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return PropertyMapClustersResponse.unclustered(7, List.of());
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<PropertyMapClustersResponse>> waiters = new ArrayList<>();
            for (int i = 1; i < threads; i++) {
                waiters.add(pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, loader(99))));
            }
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).total()).isEqualTo(7);
            for (Future<PropertyMapClustersResponse> waiter : waiters) {
                assertThat(waiter.get(5, TimeUnit.SECONDS).total()).isEqualTo(7);
            }
            assertThat(loads).hasValue(1);
            verify(store, times(1)).find(any(), any());
            verify(store, times(1)).save(any(), any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("DB 읽기가 실패하면 Redis 에 쓰지 않는다")
    void failedLoadIsNotSavedToRedis() {
        assertThatThrownBy(() -> cache.getOrLoad(FILTER, BOX, GRID, GRID, () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);

        verify(store, never()).save(any(), any(), any());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
