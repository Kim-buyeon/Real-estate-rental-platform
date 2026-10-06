package com.duri.rentalplatform.domain.property.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse.Cluster;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse.GradeCounts;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.MapClusterCacheEntry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MapClusterCacheStore} 검증 — 키 형식 · 값 왕복(JSON → gzip → Base64) · Redis 실패 흡수. Redis 는 목이다.
 * 컨테이너를 쓰지 않는다.
 */
class MapClusterCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(1);
    private static final String CACHE_KEY = "filter|box=37.52,37.58,126.81,126.89|rows=12,cols=12";

    private ValueOperations<String, String> ops;
    private MapClusterCacheStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new MapClusterCacheStore(redis, JsonMapper.builder().build(), TTL);
    }

    /** 칸 경계는 NUMERIC(10, 7) 로 맞춘 BigDecimal — scale 이 왕복에서 보존되어야 한다. */
    private static MapClusterCacheEntry entry() {
        Cluster cluster = new Cluster("3:4", new BigDecimal("37.5312345"), new BigDecimal("126.8512345"), 12,
                new GradeCounts(5, 4, 2, 1),
                new BigDecimal("37.5200000"), new BigDecimal("37.5300000"),
                new BigDecimal("126.8100000"), new BigDecimal("126.8200000"));
        Cluster zeroLike = new Cluster("0:0", new BigDecimal("0E-7"), new BigDecimal("1.0000000"), 1,
                new GradeCounts(0, 0, 0, 1),
                new BigDecimal("0E-7"), new BigDecimal("0.1000000"),
                new BigDecimal("100.0000000"), new BigDecimal("100.1000000"));
        PropertyMarkerResponse marker = new PropertyMarkerResponse(9L, new BigDecimal("37.5400000"),
                new BigDecimal("126.8300000"), 30_000L, RiskGrade.SAFE, ContractType.DEPOSIT_ONLY, 0L, "강서구",
                new BigDecimal("0.55"), Boolean.FALSE);
        PropertyMarkerResponse unanalyzed = new PropertyMarkerResponse(10L, new BigDecimal("37.5500000"),
                new BigDecimal("126.8400000"), 1L, null, ContractType.MONTHLY_RENT, 50L, "강서구", null, null);
        return new MapClusterCacheEntry(Instant.parse("2026-10-07T01:02:03.123456789Z"),
                PropertyMapClustersResponse.clustered(14, List.of(cluster, zeroLike), List.of(marker, unanalyzed)));
    }

    // ---------- 키 ----------

    @Test
    @DisplayName("Redis 키는 property:map-clusters:g{세대}:{캐시 키} 다")
    void keyFormat() {
        assertThat(MapClusterCacheStore.key("3", CACHE_KEY))
                .isEqualTo("property:map-clusters:g3:" + CACHE_KEY);
    }

    @Test
    @DisplayName("세대가 다르면 다른 키다")
    void generationSeparatesKeys() {
        assertThat(MapClusterCacheStore.key("3", CACHE_KEY)).isNotEqualTo(MapClusterCacheStore.key("4", CACHE_KEY));
    }

    @Test
    @DisplayName("TTL 은 설정값이다")
    void ttlIsConfigured() {
        assertThat(store.ttl()).isEqualTo(TTL);
    }

    // ---------- 왕복 ----------

    @Test
    @DisplayName("encode → decode 는 값을 그대로 돌려준다 — BigDecimal scale · Instant 나노초 · null 필드 포함")
    void roundTripPreservesEverything() {
        MapClusterCacheEntry original = entry();

        MapClusterCacheEntry restored = store.decode(store.encode(original));

        assertThat(restored).isEqualTo(original);
        Cluster restoredCluster = restored.response().clusters().get(0);
        assertThat(restoredCluster.minLat().scale()).isEqualTo(7);
        assertThat(restoredCluster.minLat()).isEqualTo(new BigDecimal("37.5200000"));
        assertThat(restored.response().clusters().get(1).latitude().scale()).isEqualTo(7);
        assertThat(restored.savedAt()).isEqualTo(original.savedAt());
    }

    @Test
    @DisplayName("묶지 않은 빈 응답도 왕복한다")
    void roundTripEmptyResponse() {
        MapClusterCacheEntry original = new MapClusterCacheEntry(Instant.parse("2026-10-07T00:00:00Z"),
                PropertyMapClustersResponse.unclustered(0, List.of()));

        assertThat(store.decode(store.encode(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("encode 결과는 gzip 으로 줄어든 Base64 다 — 반복이 많은 값은 원본 JSON 보다 짧다")
    void encodedIsCompressed() throws Exception {
        MapClusterCacheEntry original = entry();
        String json = JsonMapper.builder().build().writeValueAsString(original);

        String encoded = store.encode(original);

        assertThat(encoded).matches("[A-Za-z0-9+/]+={0,2}");
        assertThat(encoded).isNotEqualTo(json);
    }

    // ---------- 읽기 ----------

    @Test
    @DisplayName("find 는 세대 · 캐시 키의 Redis 키로 읽어 해독한 값을 준다")
    void findReturnsDecodedValue() {
        MapClusterCacheEntry original = entry();
        when(ops.get(MapClusterCacheStore.key("3", CACHE_KEY))).thenReturn(store.encode(original));

        Optional<MapClusterCacheEntry> found = store.find("3", CACHE_KEY);

        assertThat(found).contains(original);
    }

    @Test
    @DisplayName("find 는 키가 없으면 빈 값이다")
    void findMissingIsEmpty() {
        assertThat(store.find("3", CACHE_KEY)).isEmpty();
    }

    @Test
    @DisplayName("find 는 Redis 가 예외를 던지면 빈 값이다 — 예외를 올리지 않는다")
    void findRedisFailureIsEmpty() {
        when(ops.get(any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.find("3", CACHE_KEY)).isEmpty();
    }

    @Test
    @DisplayName("find 는 깨진 값을 빗나감으로 다룬다")
    void findCorruptValueIsEmpty() {
        when(ops.get(any())).thenReturn("이건-Base64-가-아니다");
        assertThat(store.find("3", CACHE_KEY)).isEmpty();

        when(ops.get(any())).thenReturn("AAAA");
        assertThat(store.find("3", CACHE_KEY)).isEmpty();
    }

    // ---------- 쓰기 ----------

    @Test
    @DisplayName("save 는 Redis 키에 TTL 과 함께 해독 가능한 값을 쓴다")
    void saveWritesKeyValueAndTtl() {
        MapClusterCacheEntry original = entry();

        store.save("3", CACHE_KEY, original);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(MapClusterCacheStore.key("3", CACHE_KEY)), value.capture(), eq(TTL));
        assertThat(store.decode(value.getValue())).isEqualTo(original);
    }

    @Test
    @DisplayName("save 는 Redis 가 예외를 던져도 올리지 않는다")
    void saveRedisFailureIsIgnored() {
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(any(), any(), any(Duration.class));

        assertThatCode(() -> store.save("3", CACHE_KEY, entry())).doesNotThrowAnyException();
    }
}
