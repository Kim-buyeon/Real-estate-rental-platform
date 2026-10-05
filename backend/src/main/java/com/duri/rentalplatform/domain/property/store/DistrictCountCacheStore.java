package com.duri.rentalplatform.domain.property.store;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자치구 집계 캐시. API 명세서(매물) 1.5 「동일한 필터 조합에 대한 응답은 캐싱」. 두 층이다.
 *
 * <ol>
 *   <li><b>슬롯 로컬</b>(Caffeine) — 적중하면 Redis 도 DB 도 부르지 않는다. 크기 상한 · 만료는 설정
 *       {@code property.district-counts.local-*}. 항목의 수명은 그 만료와 「집계 시각 + Redis TTL」 중 이른 쪽이다 — Redis 에서 묵은
 *       값을 담아도 응답이 낡을 수 있는 상한이 Redis TTL 을 넘지 않는다</li>
 *   <li><b>Redis</b> — 슬롯끼리 나눠 쓴다. 키는 세대 + 정규화한 필터 조합이다 — 파라미터 순서나 등급 배열의 순서 · 중복이 달라도
 *       같은 조합이면 같은 키다</li>
 * </ol>
 *
 * <p><b>세대</b> — Redis 값 {@value #GENERATION_KEY}. 매물 수 · 등급 분포를 바꾸는 작업(매물 갱신 · 등기 재조회 · Mock 대장 교체 배치, 초기 적재)이 끝나면
 * {@link #bumpGeneration()} 이 올리고 자기 슬롯의 로컬을 비운다. 다른 슬롯은 반복 작업이 {@link #refreshGeneration()} 으로 읽어
 * 바뀌었으면 비운다. 요청 경로는 세대를 읽지 않는다 — 기동 직후 아직 모를 때만 읽는다. 세대가 바뀌면 옛 세대의 Redis 키는 읽히지
 * 않고 TTL 로 사라진다. 발행 · 구독을 쓰지 않는 이유는 기준표 캐시와 같다(끊긴 동안 온 메시지를 잃는다) — 아키텍처 설계서(성능)
 * 1.3.
 *
 * <p><b>같은 키 동시 빗나감</b> — 슬롯 안에서 하나만 Redis · DB 로 가고 나머지는 그 결과를 기다린다. 읽기는 <b>부른 스레드에서</b>
 * 한다 — 서비스의 읽기 트랜잭션 · 읽기 분산 표시(@ReplicaRead)가 스레드에 묶여 있어 다른 스레드로 넘기면 풀이 바뀐다. 읽기가
 * 실패하면 기다리던 요청도 같은 예외를 받고, 실패는 담지 않는다.
 *
 * <p>{@code RedisConfig} 의 {@code RedisTemplate<String, Object>} 를 쓰지 않는다. 그 직렬화기는 기본 타이핑이라 final 인 record
 * 최상위 값에 타입 힌트가 붙지 않아 되읽을 때 구체 타입을 알 수 없다. 문자열 템플릿에 JSON 을 넣고 읽을 때 타입을 지정한다.
 *
 * <p>Redis 장애는 조회 실패로 번지지 않게 한다. 캐시 · 세대를 못 읽거나 못 쓰면 경고만 남기고 DB 집계로 간다.
 */
@Slf4j
@Component
public class DistrictCountCacheStore {

    public static final String GENERATION_KEY = "property:district-counts:gen";

    private static final String KEY_PREFIX = "property:district-counts:";

    /** 세대 키가 없을 때의 세대. 키가 지워져도 이 값으로 이어 간다. */
    static final String INITIAL_GENERATION = "0";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration redisTtl;
    private final Duration localTtl;
    private final Clock clock;
    private final AsyncCache<String, DistrictCountsResponse> local;

    /** 지금 세대. 아직 모르면 null(기동 직후). 쓰기는 {@code synchronized} 메서드만 한다. */
    private volatile String generation;
    /** 세대 키 읽기 실패가 이어지는 중인가 — 경고를 실패가 시작될 때만 남긴다. */
    private boolean generationReadFailing;

    @Autowired
    public DistrictCountCacheStore(
            StringRedisTemplate redis,
            JsonMapper jsonMapper,
            @Value("${property.district-counts.cache-ttl}") Duration redisTtl,
            @Value("${property.district-counts.local-ttl}") Duration localTtl,
            @Value("${property.district-counts.local-max-size}") long localMaxSize) {
        this(redis, jsonMapper, redisTtl, localTtl, localMaxSize, Ticker.systemTicker(), Clock.systemUTC(),
                ForkJoinPool.commonPool());
    }

    /** 테스트용 — 시간과 Caffeine 정리 실행기를 바꿔 끼운다. 운영은 위 생성자(Caffeine 기본값과 같은 공용 풀). */
    DistrictCountCacheStore(
            StringRedisTemplate redis,
            JsonMapper jsonMapper,
            Duration redisTtl,
            Duration localTtl,
            long localMaxSize,
            Ticker ticker,
            Clock clock,
            Executor maintenanceExecutor) {
        if (localTtl.compareTo(redisTtl) >= 0) {
            throw new IllegalArgumentException(
                    "로컬 만료(" + localTtl + ")는 Redis TTL(" + redisTtl + ")보다 짧아야 한다");
        }
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.redisTtl = redisTtl;
        this.localTtl = localTtl;
        this.clock = clock;
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
        String gen = generation;
        if (gen == null) {
            gen = readGenerationOnFirstUse();
            if (gen == null) {
                // 세대를 모르면 어느 키가 지금 것인지 알 수 없다 — 담지 않고 DB 로 간다(Redis 장애 중의 예전 동작과 같다).
                return loader.get();
            }
        }
        String filterKey = filterKey(filter);
        String localKey = gen + "|" + filterKey;

        CompletableFuture<DistrictCountsResponse> mine = new CompletableFuture<>();
        CompletableFuture<DistrictCountsResponse> existing = local.asMap().putIfAbsent(localKey, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            String redisKey = redisKey(gen, filterKey);
            DistrictCountsResponse response = find(redisKey).orElseGet(() -> {
                DistrictCountsResponse loaded = loader.get();
                save(redisKey, loaded);
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
     * 반복 작업용. 세대 키를 읽어 바뀌었으면 로컬을 비운다. 읽기 실패는 경고를 실패가 시작될 때와 회복될 때만 남기고 지금 세대를
     * 유지한다 — 그동안 다른 슬롯이 올린 세대는 로컬 만료 안에 따라온다.
     */
    public synchronized void refreshGeneration() {
        String read;
        try {
            read = readGeneration();
        } catch (RuntimeException e) {
            if (!generationReadFailing) {
                generationReadFailing = true;
                log.warn("자치구 집계 세대 키를 읽지 못했다 — 회복될 때까지 이 경고를 다시 남기지 않는다", e);
            }
            return;
        }
        if (generationReadFailing) {
            generationReadFailing = false;
            log.info("자치구 집계 세대 키 읽기가 회복됐다");
        }
        switchTo(read);
    }

    /**
     * 매물을 바꾼 작업이 끝난 뒤(모든 커밋 뒤) 부른다. 세대를 올리고 이 슬롯의 로컬을 비운다. 다른 슬롯은
     * {@link #refreshGeneration()} 주기 안에 따라온다. Redis 실패는 경고만 남긴다 — 변경은 이미 저장됐고, 다른 슬롯은 로컬 만료 ·
     * Redis TTL 안에 새 집계를 읽는다.
     */
    public synchronized void bumpGeneration() {
        try {
            Long next = redis.opsForValue().increment(GENERATION_KEY);
            if (next != null) {
                generation = next.toString();
            }
        } catch (RuntimeException e) {
            log.warn("자치구 집계 세대 키를 올리지 못했다 — 다른 슬롯은 로컬 만료 · Redis TTL 안에 새 집계를 읽는다", e);
        } finally {
            local.synchronous().invalidateAll();
        }
    }

    /** 기동 직후 세대를 아직 모를 때만 요청 경로에서 읽는다. 실패하면 null — 다음 요청이나 반복 작업이 다시 읽는다. */
    private synchronized String readGenerationOnFirstUse() {
        if (generation != null) {
            return generation;
        }
        try {
            switchTo(readGeneration());
            return generation;
        } catch (RuntimeException e) {
            log.warn("자치구 집계 세대 키를 읽지 못했다 — 캐시 없이 DB 집계로 진행", e);
            return null;
        }
    }

    private String readGeneration() {
        String value = redis.opsForValue().get(GENERATION_KEY);
        return value == null ? INITIAL_GENERATION : value;
    }

    /** 세대를 바꾼다. 바뀌었으면 로컬을 비운다 — 로컬 키에 세대가 들어 있어 남겨도 읽히지 않지만 자리를 차지한다. */
    private void switchTo(String read) {
        if (read.equals(generation)) {
            return;
        }
        generation = read;
        local.synchronous().invalidateAll();
    }

    private Optional<DistrictCountsResponse> find(String redisKey) {
        try {
            String json = redis.opsForValue().get(redisKey);
            return json == null
                    ? Optional.empty()
                    : Optional.of(jsonMapper.readValue(json, DistrictCountsResponse.class));
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 읽기 실패 — DB 집계로 진행", e);
            return Optional.empty();
        }
    }

    private void save(String redisKey, DistrictCountsResponse response) {
        try {
            redis.opsForValue().set(redisKey, jsonMapper.writeValueAsString(response), redisTtl);
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 쓰기 실패", e);
        }
    }

    /** 로컬 항목의 수명 — 로컬 만료와 「집계 시각 + Redis TTL」까지 남은 시간 중 짧은 쪽. 집계 시각이 없으면 로컬 만료. */
    private Duration localLifetime(DistrictCountsResponse value) {
        if (value.aggregatedAt() == null) {
            return localTtl;
        }
        Duration remaining = Duration.between(clock.instant(), value.aggregatedAt().toInstant().plus(redisTtl));
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

    static String redisKey(String generation, String filterKey) {
        return KEY_PREFIX + "g" + generation + ":" + filterKey;
    }

    /** 필터를 고정 순서의 문자열로 정규화한다. 빈 값은 비어 있는 칸으로 둔다. */
    static String filterKey(DistrictCountRequest f) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add("district=" + blankToEmpty(f.district()));
        joiner.add("contractType=" + str(f.contractType()));
        joiner.add("depositMin=" + str(f.depositMin()));
        joiner.add("depositMax=" + str(f.depositMax()));
        joiner.add("monthlyRentMax=" + str(f.monthlyRentMax()));
        joiner.add("propertyType=" + str(f.propertyType()));
        joiner.add("riskGrade=" + grades(f.riskGrade()));
        joiner.add("areaMin=" + (f.areaMin() == null ? "" : f.areaMin().stripTrailingZeros().toPlainString()));
        joiner.add("areaMax=" + (f.areaMax() == null ? "" : f.areaMax().stripTrailingZeros().toPlainString()));
        return joiner.toString();
    }

    private static String grades(List<RiskGrade> grades) {
        if (grades == null) {
            return "";
        }
        TreeSet<RiskGrade> sorted = new TreeSet<>();
        grades.stream().filter(Objects::nonNull).forEach(sorted::add);
        StringJoiner joiner = new StringJoiner(",");
        sorted.forEach(g -> joiner.add(g.name()));
        return joiner.toString();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
