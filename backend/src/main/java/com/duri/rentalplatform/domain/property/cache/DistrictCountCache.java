package com.duri.rentalplatform.domain.property.cache;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 자치구 집계의 슬롯 로컬 캐시(Caffeine)와 세대 관리. 아키텍처 설계서(성능) 1.3 의 예외 — 슬롯 메모리에 두되 무효화 경로(Redis
 * 세대 키)가 있다. Redis 층은 {@link DistrictCountCacheStore}.
 *
 * <ol>
 *   <li><b>로컬</b> — 적중하면 Redis 도 DB 도 부르지 않는다. 크기 상한 · 만료는 설정 {@code property.district-counts.local-*}.
 *       항목의 수명은 그 만료와 「집계 시각 + Redis TTL」 중 이른 쪽이다 — Redis 에서 묵은 값을 담아도 응답이 낡을 수 있는 상한이
 *       Redis TTL 을 넘지 않는다</li>
 *   <li><b>Redis</b> — 슬롯끼리 나눠 쓴다. 키에 세대가 든다</li>
 * </ol>
 *
 * <p><b>세대</b> — 매물 수 · 등급 분포를 바꾸는 작업(매물 갱신 · 등기 재조회 · Mock 대장 교체 배치, 초기 적재)이 끝나면
 * {@link #bumpGeneration()} 이 올리고 자기 슬롯의 로컬을 비운다. 다른 슬롯은 반복 작업이 {@link #refreshGeneration()} 으로 읽어
 * 바뀌었으면 비운다. 발행 · 구독을 쓰지 않는 이유는 기준표 캐시와 같다(끊긴 동안 온 메시지를 잃는다).
 *
 * <p><b>요청 경로</b> — 세대를 읽지 않는다. 예외 하나 — 기동 뒤 세대를 아직 한 번도 읽지 못했으면 요청이 읽는다. 이때도 슬롯당 확인
 * 간격({@code property.district-counts.generation-check-interval})에 한 번만, 다른 읽기가 진행 중이면 기다리지 않는다. 읽지
 * 않았거나 못 읽은 요청은 캐시 없이 DB 로 간다 — Redis 가 느릴 때(타임아웃 2초) 요청이 줄을 서지 않게 한다.
 *
 * <p><b>같은 키 동시 빗나감</b> — 슬롯 안에서 하나만 Redis · DB 로 가고 나머지는 그 결과를 기다린다. 읽기는 <b>부른 스레드에서</b>
 * 한다 — 서비스의 읽기 트랜잭션 · 읽기 분산 표시(@ReplicaRead)가 스레드에 묶여 있어 다른 스레드로 넘기면 풀이 바뀐다. 읽기가
 * 실패하면 기다리던 요청도 같은 예외를 받고, 실패는 담지 않는다.
 *
 * <p><b>세대를 함께 쓰는 캐시</b> — 같은 배치가 같은 순간에 바꾸는 다른 매물 응답의 슬롯 로컬 캐시(지도 묶음 —
 * {@link MapClusterCache})도 이 세대를 쓴다. 세대 키 · 확인 반복 작업 · 올리기는 하나이고, 그 캐시는
 * {@link #generationForRequest()} 로 키에 세대를 넣고 {@link #addInvalidationListener(Runnable)} 로 비울 때를 함께 받는다.
 */
@Slf4j
@Component
public class DistrictCountCache {

    private final DistrictCountCacheStore store;
    private final Duration localTtl;
    private final long requestReadIntervalNanos;
    private final Ticker ticker;
    private final Clock clock;
    private final AsyncCache<String, DistrictCountsResponse> local;
    /** 이 슬롯의 로컬을 비울 때 함께 부를 것 — 세대를 함께 쓰는 다른 캐시의 비우기. */
    private final List<Runnable> invalidationListeners = new CopyOnWriteArrayList<>();

    /** 세대를 바꾸는 쪽(반복 작업 · 올리기 · 첫 읽기)을 하나로 줄 세운다. 요청 경로는 tryLock 만 한다. */
    private final ReentrantLock generationLock = new ReentrantLock();
    /** 지금 세대. 아직 모르면 null(기동 직후). 쓰기는 {@link #generationLock} 을 쥔 쪽만 한다. */
    private volatile String generation;
    /** 세대 키 읽기 실패가 이어지는 중인가 — 경고를 실패가 시작될 때만 남긴다. {@link #generationLock} 을 쥔 쪽만 쓴다. */
    private boolean generationReadFailing;
    /** 요청 경로가 마지막으로 세대 읽기를 시도한 시각(ticker). 처음엔 간격만큼 이전으로 둬 첫 요청은 바로 읽는다. */
    private volatile long lastRequestReadNanos;

    @Autowired
    public DistrictCountCache(
            DistrictCountCacheStore store,
            @Value("${property.district-counts.local-ttl}") Duration localTtl,
            @Value("${property.district-counts.local-max-size}") long localMaxSize,
            @Value("${property.district-counts.generation-check-interval}") Duration generationCheckInterval) {
        this(store, localTtl, localMaxSize, generationCheckInterval, Ticker.systemTicker(), Clock.systemUTC(),
                ForkJoinPool.commonPool());
    }

    /** 테스트용 — 시간과 Caffeine 정리 실행기를 바꿔 끼운다. 운영은 위 생성자(Caffeine 기본값과 같은 공용 풀). */
    DistrictCountCache(
            DistrictCountCacheStore store,
            Duration localTtl,
            long localMaxSize,
            Duration generationCheckInterval,
            Ticker ticker,
            Clock clock,
            Executor maintenanceExecutor) {
        if (localTtl.compareTo(store.ttl()) >= 0) {
            throw new IllegalArgumentException(
                    "로컬 만료(" + localTtl + ")는 Redis TTL(" + store.ttl() + ")보다 짧아야 한다");
        }
        this.store = store;
        this.localTtl = localTtl;
        this.requestReadIntervalNanos = generationCheckInterval.toNanos();
        this.ticker = ticker;
        this.clock = clock;
        this.lastRequestReadNanos = ticker.read() - requestReadIntervalNanos;
        this.local = Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfter(Expiry.<String, DistrictCountsResponse>creating((key, value) -> localLifetime(value)))
                .ticker(ticker)
                .executor(maintenanceExecutor)
                .buildAsync();
    }

    /**
     * 필터 조합의 집계. 로컬 → Redis → {@code loader}(DB) 순으로 찾고, 찾은 층보다 위의 층을 채운다.
     *
     * @param loader DB 집계. 부른 스레드에서 실행된다. 던진 예외는 그대로 올라가고 담기지 않는다
     */
    public DistrictCountsResponse getOrLoad(DistrictCountRequest filter, Supplier<DistrictCountsResponse> loader) {
        String gen = generationForRequest();
        if (gen == null) {
            // 세대를 모르면 어느 키가 지금 것인지 알 수 없다 — 담지 않고 DB 로 간다(Redis 장애 중의 예전 동작과 같다).
            return loader.get();
        }
        String localKey = gen + "|" + DistrictCountCacheStore.filterKey(filter);

        CompletableFuture<DistrictCountsResponse> mine = new CompletableFuture<>();
        CompletableFuture<DistrictCountsResponse> existing = local.asMap().putIfAbsent(localKey, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            String generationOfLoad = gen;
            DistrictCountsResponse response = store.find(generationOfLoad, filter).orElseGet(() -> {
                DistrictCountsResponse loaded = loader.get();
                store.save(generationOfLoad, filter, loaded);
                return loaded;
            });
            mine.complete(response);
            return response;
        } catch (Throwable e) {
            // 실패한 항목은 Caffeine 이 지운다 — 다음 요청이 다시 읽는다. 기다리던 요청은 같은 예외를 받는다.
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * 요청 경로에서 쓸 지금 세대. 아직 모르면(기동 직후) 슬롯당 확인 간격에 한 번 읽어 보되 기다리지 않는다 — 읽지 않았거나 못
     * 읽었으면 null 이고, 부른 쪽은 캐시 없이 DB 로 간다. 세대를 함께 쓰는 캐시도 이것을 부른다.
     */
    public String generationForRequest() {
        String gen = generation;
        return gen != null ? gen : readGenerationOnRequestPath();
    }

    /**
     * 이 슬롯의 로컬을 비울 때(세대가 바뀌었거나 이 슬롯이 올렸을 때) 함께 부를 것을 등록한다. 세대 잠금을 쥔 채 불리므로 짧아야
     * 하고 Redis · DB 를 부르지 않아야 한다.
     */
    public void addInvalidationListener(Runnable listener) {
        invalidationListeners.add(listener);
    }

    /**
     * 반복 작업용. 세대 키를 읽어 바뀌었으면 로컬을 비운다. 읽기 실패는 경고를 실패가 시작될 때와 회복될 때만 남기고 지금 세대를
     * 유지한다 — 그동안 다른 슬롯이 올린 세대는 로컬 만료 안에 따라온다.
     */
    public void refreshGeneration() {
        generationLock.lock();
        try {
            readAndSwitchLocked();
        } finally {
            generationLock.unlock();
        }
    }

    /**
     * 매물을 바꾼 작업이 끝난 뒤(모든 커밋 뒤) 부른다. 세대를 올리고 이 슬롯의 로컬을 비운다. 다른 슬롯은
     * {@link #refreshGeneration()} 주기 안에 따라온다. Redis 실패는 경고만 남긴다 — 변경은 이미 저장됐고, 다른 슬롯은 로컬 만료 ·
     * Redis TTL 안에 새 집계를 읽는다.
     */
    public void bumpGeneration() {
        generationLock.lock();
        try {
            String next = store.incrementGeneration();
            if (next != null) {
                generation = next;
            }
        } catch (RuntimeException e) {
            log.warn("자치구 집계 세대 키를 올리지 못했다 — 다른 슬롯은 로컬 만료 · Redis TTL 안에 새 집계를 읽는다", e);
        } finally {
            invalidateLocal();
            generationLock.unlock();
        }
    }

    /**
     * 기동 뒤 세대를 아직 모를 때 요청 경로에서 읽는다. 슬롯당 확인 간격에 한 번만 시도하고, 다른 쪽이 세대를 다루는 중이면
     * 기다리지 않는다. 시도하지 않았거나 실패하면 null.
     */
    private String readGenerationOnRequestPath() {
        long now = ticker.read();
        long last = lastRequestReadNanos;
        if (now - last < requestReadIntervalNanos || !generationLock.tryLock()) {
            return null;
        }
        try {
            if (generation != null) {
                return generation;
            }
            if (lastRequestReadNanos != last) {
                return null;
            }
            lastRequestReadNanos = now;
            readAndSwitchLocked();
            return generation;
        } finally {
            generationLock.unlock();
        }
    }

    /** {@link #generationLock} 을 쥔 채 부른다. */
    private void readAndSwitchLocked() {
        String read;
        try {
            read = store.readGeneration();
        } catch (RuntimeException e) {
            if (!generationReadFailing) {
                generationReadFailing = true;
                log.warn("자치구 집계 세대 키를 읽지 못했다 — 회복될 때까지 이 경고를 다시 남기지 않는다. 그동안 세대를 모르는 "
                        + "요청은 캐시 없이 DB 로 간다", e);
            }
            return;
        }
        if (generationReadFailing) {
            generationReadFailing = false;
            log.info("자치구 집계 세대 키 읽기가 회복됐다");
        }
        if (read.equals(generation)) {
            return;
        }
        generation = read;
        // 로컬 키에 세대가 들어 있어 남겨도 읽히지 않지만 자리를 차지한다.
        invalidateLocal();
    }

    /** 이 슬롯의 로컬과 세대를 함께 쓰는 캐시를 비운다. */
    private void invalidateLocal() {
        local.synchronous().invalidateAll();
        invalidationListeners.forEach(Runnable::run);
    }

    /** 로컬 항목의 수명 — 로컬 만료와 「집계 시각 + Redis TTL」까지 남은 시간 중 짧은 쪽. 집계 시각이 없으면 로컬 만료. */
    private Duration localLifetime(DistrictCountsResponse value) {
        if (value.aggregatedAt() == null) {
            return localTtl;
        }
        Duration remaining = Duration.between(clock.instant(), value.aggregatedAt().toInstant().plus(store.ttl()));
        if (remaining.isNegative()) {
            return Duration.ZERO;
        }
        return remaining.compareTo(localTtl) < 0 ? remaining : localTtl;
    }

    private static DistrictCountsResponse await(CompletableFuture<DistrictCountsResponse> future) {
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
