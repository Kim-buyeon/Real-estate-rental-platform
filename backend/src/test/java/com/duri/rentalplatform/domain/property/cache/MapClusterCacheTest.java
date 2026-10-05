package com.duri.rentalplatform.domain.property.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.github.benmanes.caffeine.cache.Ticker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MapClusterCache} 검증 — 적중 · 키 · 세대 공유 · 만료 · 무게 상한 · 같은 키 동시 빗나감 · 실패. 세대는 실제
 * {@link DistrictCountCache} 에 목 Redis 를 끼우고, 시간은 테스트가 움직인다. 컨테이너를 쓰지 않는다.
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

    private ValueOperations<String, String> ops;
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
        cache = newCache(20_000);
        loads = new AtomicInteger();
    }

    private MapClusterCache newCache(long maxWeight) {
        return new MapClusterCache(generationSource, LOCAL_TTL, maxWeight, ticker, Runnable::run);
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
    @DisplayName("같은 필터 · 영역 · 격자면 두 번째 요청은 DB 를 부르지 않는다")
    void sameKeyHitsLocal() {
        cache.getOrLoad(FILTER, BOX, GRID, loader(7));

        PropertyMapClustersResponse second = cache.getOrLoad(FILTER, BOX, GRID, loader(99));

        assertThat(second.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("영역 · 필터 · 격자 칸 수 중 하나라도 다르면 다른 항목이다")
    void anyDifferenceIsDifferentEntry() {
        cache.getOrLoad(FILTER, BOX, GRID, loader(1));

        cache.getOrLoad(FILTER, new BoundingBox(37.52, 37.58, 126.81, 126.8901), GRID, loader(2));
        cache.getOrLoad(new DistrictCountRequest("강서구", null, null, null, null, null, null, null, null),
                BOX, GRID, loader(3));
        cache.getOrLoad(FILTER, BOX, GRID + 1, loader(4));

        assertThat(loads).hasValue(4);
    }

    @Test
    @DisplayName("같은 double 이면 같은 키다 — 요청 문자열의 끝자리 0 · -0.0 은 키를 바꾸지 않는다")
    void coordinateKeyIsNormalized() {
        BoundingBox written = new BoundingBox(Double.parseDouble("37.5200"), Double.parseDouble("37.580"),
                Double.parseDouble("126.81"), Double.parseDouble("126.890"));
        assertThat(MapClusterCache.key(FILTER, written, GRID)).isEqualTo(MapClusterCache.key(FILTER, BOX, GRID));

        BoundingBox negativeZero = new BoundingBox(-0.0, 0.0, -0.0, 0.0);
        BoundingBox zero = new BoundingBox(0.0, 0.0, 0.0, 0.0);
        assertThat(MapClusterCache.key(FILTER, negativeZero, GRID)).isEqualTo(MapClusterCache.key(FILTER, zero, GRID));
    }

    @Test
    @DisplayName("키는 필터 키 · 영역 네 값 · 격자 칸 수를 담는다")
    void keyContents() {
        assertThat(MapClusterCache.key(FILTER, BOX, GRID))
                .isEqualTo(DistrictCountCacheStore.filterKey(FILTER) + "|box=37.52,37.58,126.81,126.89|grid=12");
    }

    // ---------- 세대 ----------

    @Test
    @DisplayName("반복 작업이 바뀐 세대를 읽으면 지도 묶음 캐시도 비운다")
    void generationChangeClearsMapClusters() {
        generationSource.refreshGeneration();
        cache.getOrLoad(FILTER, BOX, GRID, loader(7));

        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenReturn("4");
        generationSource.refreshGeneration();
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, loader(8));

        assertThat(after.total()).isEqualTo(8);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("배치 끝에 세대를 올리면 지도 묶음 캐시도 비운다")
    void bumpClearsMapClusters() {
        generationSource.refreshGeneration();
        cache.getOrLoad(FILTER, BOX, GRID, loader(7));
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(4L);

        generationSource.bumpGeneration();
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, loader(8));

        assertThat(after.total()).isEqualTo(8);
    }

    @Test
    @DisplayName("옛 세대로 시작한 읽기가 비우기 뒤에 끝나 담겨도 새 세대 요청은 그것을 읽지 않는다")
    void staleLoadFinishingAfterBumpIsNotServedToNewGeneration() {
        generationSource.refreshGeneration();
        when(ops.increment(DistrictCountCacheStore.GENERATION_KEY)).thenReturn(4L);

        // 읽기 도중에 배치가 끝나 세대가 오르고 캐시가 비워진다 — 읽기는 그 뒤에 끝나 옛 세대 키로 담긴다.
        PropertyMapClustersResponse stale = cache.getOrLoad(FILTER, BOX, GRID, () -> {
            loads.incrementAndGet();
            generationSource.bumpGeneration();
            return PropertyMapClustersResponse.unclustered(7, List.of());
        });
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, loader(8));

        assertThat(stale.total()).isEqualTo(7);
        assertThat(after.total()).isEqualTo(8);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("세대를 읽지 못하면 담지 않고 매번 DB 로 간다")
    void unknownGenerationBypassesCache() {
        when(ops.get(DistrictCountCacheStore.GENERATION_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        cache.getOrLoad(FILTER, BOX, GRID, loader(7));
        advance(CHECK_INTERVAL);
        cache.getOrLoad(FILTER, BOX, GRID, loader(8));

        assertThat(loads).hasValue(2);
    }

    // ---------- 만료 · 크기 ----------

    @Test
    @DisplayName("만료가 지나면 DB 에서 다시 읽는다")
    void expiresAfterLocalTtl() {
        cache.getOrLoad(FILTER, BOX, GRID, loader(7));

        advance(LOCAL_TTL.minusSeconds(1));
        cache.getOrLoad(FILTER, BOX, GRID, loader(8));
        assertThat(loads).hasValue(1);

        advance(Duration.ofSeconds(1));
        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, loader(9));
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
        cache.getOrLoad(FILTER, BOX, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });
        cache.getOrLoad(FILTER, otherBox, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });

        cache.getOrLoad(FILTER, BOX, GRID, () -> {
            loads.incrementAndGet();
            return withMarkers(60);
        });
        cache.getOrLoad(FILTER, otherBox, GRID, () -> {
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
            Future<PropertyMapClustersResponse> first = pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return PropertyMapClustersResponse.unclustered(7, List.of());
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<PropertyMapClustersResponse>> waiters = new ArrayList<>();
            for (int i = 1; i < threads; i++) {
                waiters.add(pool.submit(() -> cache.getOrLoad(FILTER, BOX, GRID, loader(99))));
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
        assertThatThrownBy(() -> cache.getOrLoad(FILTER, BOX, GRID, () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);

        PropertyMapClustersResponse after = cache.getOrLoad(FILTER, BOX, GRID, loader(7));

        assertThat(after.total()).isEqualTo(7);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("읽기는 부른 스레드에서 한다 — 읽기 트랜잭션 · 읽기 분산 표시가 스레드에 묶여 있다")
    void loadsOnCallingThread() {
        Thread caller = Thread.currentThread();
        List<Thread> loadThread = new ArrayList<>();

        cache.getOrLoad(FILTER, BOX, GRID, () -> {
            loadThread.add(Thread.currentThread());
            return PropertyMapClustersResponse.unclustered(1, List.of());
        });

        assertThat(loadThread).containsExactly(caller);
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
