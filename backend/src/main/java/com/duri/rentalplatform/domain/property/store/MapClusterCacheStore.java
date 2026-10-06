package com.duri.rentalplatform.domain.property.store;

import com.duri.rentalplatform.domain.property.vo.MapClusterCacheEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지도 묶음의 Redis 층 — 담은 값({@link MapClusterCacheEntry})의 읽기 · 쓰기만 한다. 슬롯 로컬 층 · 같은 키 동시 빗나감 합치기 ·
 * 세대는 {@code MapClusterCache}(cache 패키지)가 갖는다. 세대는 자치구 집계의 세대 키를 함께 쓴다. API 명세서(매물) 1.12
 * 「서버 캐시」 · 아키텍처 설계서(성능) 1.3.
 *
 * <p>키는 {@code property:map-clusters:g{세대}:{캐시 키}} 다. 캐시 키(필터 · 표시 영역 · 격자 행 · 열 수의 정규화)는 부르는 쪽이
 * 만들어 넘긴다. 세대가 바뀌면 옛 세대의 키는 읽히지 않고 TTL 로 사라진다.
 *
 * <p>값은 JSON 을 gzip 한 뒤 Base64 문자열로 둔다 — 응답 JSON 이 자치구 집계보다 크고(크기 실측은 설정 파일) Redis 는
 * maxmemory · volatile-lru 라 차면 TTL 있는 다른 키(로그인 토큰 등)가 밀려난다. 문자열 템플릿에 넣는 이유는
 * {@link DistrictCountCacheStore} 와 같다.
 *
 * <p>읽기 · 쓰기 실패는 경고만 남긴다 — 조회 실패로 번지지 않게 한다. 읽기 실패는 빗나감으로 다뤄 DB 로 간다.
 */
@Slf4j
@Component
public class MapClusterCacheStore {

    private static final String KEY_PREFIX = "property:map-clusters:";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public MapClusterCacheStore(
            StringRedisTemplate redis,
            JsonMapper jsonMapper,
            @Value("${property.map-clusters.redis-ttl}") Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
    }

    /** Redis TTL. 로컬 층이 항목 수명의 상한을 계산할 때 쓴다. */
    public Duration ttl() {
        return ttl;
    }

    /** 세대 · 캐시 키의 값. 없거나 읽지 못하면 빈 값 — 실패는 경고만 남긴다. */
    public Optional<MapClusterCacheEntry> find(String generation, String cacheKey) {
        try {
            String encoded = redis.opsForValue().get(key(generation, cacheKey));
            return encoded == null ? Optional.empty() : Optional.of(decode(encoded));
        } catch (RuntimeException e) {
            log.warn("지도 묶음 캐시 읽기 실패 — DB 조회로 진행", e);
            return Optional.empty();
        }
    }

    /** 세대 · 캐시 키의 값을 TTL 로 쓴다. 실패는 경고만 남긴다. */
    public void save(String generation, String cacheKey, MapClusterCacheEntry entry) {
        try {
            redis.opsForValue().set(key(generation, cacheKey), encode(entry), ttl);
        } catch (RuntimeException e) {
            log.warn("지도 묶음 캐시 쓰기 실패", e);
        }
    }

    /** Redis 키. */
    public static String key(String generation, String cacheKey) {
        return KEY_PREFIX + "g" + generation + ":" + cacheKey;
    }

    /** JSON → gzip → Base64. */
    String encode(MapClusterCacheEntry entry) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(jsonMapper.writeValueAsBytes(entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** Base64 → gunzip → JSON. */
    MapClusterCacheEntry decode(String encoded) {
        byte[] compressed = Base64.getDecoder().decode(encoded);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return jsonMapper.readValue(in.readAllBytes(), MapClusterCacheEntry.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
