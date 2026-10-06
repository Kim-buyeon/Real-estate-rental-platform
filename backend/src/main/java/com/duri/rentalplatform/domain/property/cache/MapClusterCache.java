package com.duri.rentalplatform.domain.property.cache;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.property.store.MapClusterCacheStore;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.MapClusterCacheEntry;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지도 묶음 응답의 2층 캐시 — 슬롯 로컬(Caffeine) 앞, Redis({@link MapClusterCacheStore}) 뒤. API 명세서(매물) 1.12 「서버 캐시」 ·
 * 아키텍처 설계서(성능) 1.3 의 예외.
 *
 * <ol>
 *   <li><b>로컬</b> — 적중하면 Redis 도 DB 도 부르지 않는다. 요청 경로의 Redis 왕복은 로컬 빗나감에만 남는다</li>
 *   <li><b>Redis</b> — 슬롯끼리 나눠 쓴다. 로컬만 두면 슬롯마다 따로 빗나가 같은 영역을 슬롯 수만큼 DB 에서 읽는다(근거는
 *       아키텍처 설계서(성능) 1.3 「예외 셋」). 키에 세대가 든다</li>
 * </ol>
 *
 * <p><b>키</b> — 세대 + 공통 검색 필터(자치구 집계와 같은 정규화) + 표시 영역 네 값 + 격자 행 · 열 수({@code |rows=R,cols=C}). 화면이 표시 영역을 타일 격자에
 * 맞춰 보내므로(명세 1.12) 같은 지역을 보는 요청이 같은 키가 된다. 좌표는 요청이 {@code Double} 로 받은 값의
 * {@link Double#toString(double)} — 같은 double 이면 같은 문자열이라 {@code 37.5200} 과 {@code 37.52} 가 같은 키다.
 *
 * <p><b>세대</b> — 자치구 집계 캐시의 세대({@value DistrictCountCacheStore#GENERATION_KEY})를 함께 쓴다. 매물 · 판정 배치가 끝나
 * 세대가 오르면 같은 확인 반복 작업이 두 캐시의 로컬을 함께 비운다({@link DistrictCountCache#addInvalidationListener(Runnable)}).
 * 로컬 · Redis 키에 세대가 들어 있어 비우기 직전에 옛 세대로 시작한 읽기가 끝나 담겨도 새 세대의 요청은 그것을 읽지 않고, 옛 세대의
 * Redis 키는 TTL 로 사라진다. 세대를 모르면(기동 직후 · Redis 장애) 담지 않고 DB 로 간다 — 세대를 읽는 규칙(요청 경로에서
 * 기다리지 않음)은 {@link DistrictCountCache} 와 같다.
 *
 * <p><b>낡음 상한</b> — 로컬 항목의 수명은 로컬 만료({@code property.map-clusters.local-ttl})와 「DB 에서 읽은 시각 + Redis
 * TTL({@code property.map-clusters.redis-ttl})」 중 이른 쪽이다 — Redis 에서 묵은 값을 로컬에 담아도 응답이 낡을 수 있는 상한이
 * Redis TTL 을 넘지 않는다. 세대를 올리지 않는 변경(사용자 재분석)과 Redis 장애로 놓친 다른 슬롯의 세대 변경이 이 안에 보인다.
 *
 * <p><b>크기 상한</b> — 로컬은 무게(묶음 수 + 마커 수 + 1)의 합({@code property.map-clusters.local-max-weight}). 응답 크기가 표시
 * 영역의 매물 분포와 행 · 열 수(각각 1 ~ 24)에 따라 1 ~ 577 항목으로 달라 건수 상한으로는 힙 사용을 묶을 수 없다. Redis 는 TTL 과
 * 압축(보관소)으로 묶는다.
 *
 * <p><b>같은 키 동시 빗나감</b> — 슬롯 안에서 하나만 Redis · DB 로 가고 나머지는 그 결과를 기다린다. 읽기는 <b>부른 스레드에서</b>
 * 한다 — 서비스의 읽기 트랜잭션 · 읽기 분산 표시(@ReplicaRead)가 스레드에 묶여 있다. 읽기가 실패하면 기다리던 요청도 같은 예외를
 * 받고, 실패는 담지 않는다. Redis 읽기 · 쓰기 실패는 보관소가 경고만 남기고 DB 로 간다.
 */
@Component
public class MapClusterCache {

    private final DistrictCountCache generationSource;
    private final MapClusterCacheStore store;
    private final Duration localTtl;
    private final Clock clock;
    private final AsyncCache<String, MapClusterCacheEntry> local;

    @Autowired
    public MapClusterCache(
            DistrictCountCache generationSource,
            MapClusterCacheStore store,
            @Value("${property.map-clusters.local-ttl}") Duration localTtl,
            @Value("${property.map-clusters.local-max-weight}") long localMaxWeight) {
        this(generationSource, store, localTtl, localMaxWeight, Ticker.systemTicker(), Clock.systemUTC(),
                ForkJoinPool.commonPool());
    }

    /** 테스트용 — 시간과 Caffeine 정리 실행기를 바꿔 끼운다. 운영은 위 생성자(Caffeine 기본값과 같은 공용 풀). */
    MapClusterCache(
            DistrictCountCache generationSource,
            MapClusterCacheStore store,
            Duration localTtl,
            long localMaxWeight,
            Ticker ticker,
            Clock clock,
            Executor maintenanceExecutor) {
        this.generationSource = generationSource;
        this.store = store;
        this.localTtl = localTtl;
        this.clock = clock;
        this.local = Caffeine.newBuilder()
                .maximumWeight(localMaxWeight)
                .weigher((String key, MapClusterCacheEntry value) -> weight(value.response()))
                .expireAfter(Expiry.<String, MapClusterCacheEntry>creating((key, value) -> localLifetime(value)))
                .ticker(ticker)
                .executor(maintenanceExecutor)
                .buildAsync();
        generationSource.addInvalidationListener(() -> local.synchronous().invalidateAll());
    }

    /**
     * 지도 묶음. 로컬 → Redis → {@code loader}(DB) 순으로 찾고, 찾은 층보다 위의 층을 채운다.
     *
     * @param filter 공통 검색 필터
     * @param box 표시 영역 — 검증을 마친 값
     * @param rows 격자 행 수(위도 방향)
     * @param cols 격자 열 수(경도 방향)
     * @param loader DB 조회. 부른 스레드에서 실행된다. 던진 예외는 그대로 올라가고 담기지 않는다
     */
    public PropertyMapClustersResponse getOrLoad(DistrictCountRequest filter, BoundingBox box, int rows, int cols,
            Supplier<PropertyMapClustersResponse> loader) {
        String gen = generationSource.generationForRequest();
        if (gen == null) {
            return loader.get();
        }
        String cacheKey = key(filter, box, rows, cols);
        String localKey = gen + "|" + cacheKey;

        CompletableFuture<MapClusterCacheEntry> mine = new CompletableFuture<>();
        CompletableFuture<MapClusterCacheEntry> existing = local.asMap().putIfAbsent(localKey, mine);
        if (existing != null) {
            return await(existing).response();
        }
        try {
            MapClusterCacheEntry entry = store.find(gen, cacheKey).orElseGet(() -> {
                // 시각을 DB 조회 **전에** 잡는다 — 인자 평가 순서. 조회 뒤에 잡으면 로컬 수명 상한이 조회 시간만큼 늦게 끝난다
                MapClusterCacheEntry loaded = new MapClusterCacheEntry(clock.instant(), loader.get());
                store.save(gen, cacheKey, loaded);
                return loaded;
            });
            mine.complete(entry);
            return entry.response();
        } catch (Throwable e) {
            // 실패한 항목은 Caffeine 이 지운다 — 다음 요청이 다시 읽는다. 기다리던 요청은 같은 예외를 받는다.
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** 세대를 뺀 키. 필터 · 표시 영역 · 격자 행 · 열 수. */
    static String key(DistrictCountRequest filter, BoundingBox box, int rows, int cols) {
        return DistrictCountCacheStore.filterKey(filter)
                + "|box=" + coordinate(box.minLat()) + "," + coordinate(box.maxLat())
                + "," + coordinate(box.minLng()) + "," + coordinate(box.maxLng())
                + "|rows=" + rows + ",cols=" + cols;
    }

    /** 응답이 든 항목 수 + 1(빈 응답 · 키 · 캐시 노드 몫). */
    static int weight(PropertyMapClustersResponse value) {
        return 1 + value.clusters().size() + value.markers().size();
    }

    /**
     * 로컬 항목의 수명 — 로컬 만료와 「DB 에서 읽은 시각 + Redis TTL」까지 남은 시간 중 짧은 쪽. 읽은 시각이 없으면 로컬 만료.
     */
    private Duration localLifetime(MapClusterCacheEntry value) {
        if (value.savedAt() == null) {
            return localTtl;
        }
        Duration remaining = Duration.between(clock.instant(), value.savedAt().plus(store.ttl()));
        if (remaining.isNegative()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(localTtl) < 0 ? remaining : localTtl;
    }

    /** {@code + 0.0} 은 -0.0 을 0.0 으로 바꾼다 — 조회 조건에서는 같은 값이다. */
    private static String coordinate(double value) {
        return Double.toString(value + 0.0);
    }

    private static MapClusterCacheEntry await(CompletableFuture<MapClusterCacheEntry> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
