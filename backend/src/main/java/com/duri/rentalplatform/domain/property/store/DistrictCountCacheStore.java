package com.duri.rentalplatform.domain.property.store;

import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자치구 집계 캐시. API 명세서(매물) 1.5 「동일한 필터 조합에 대한 응답은 캐싱」.
 *
 * <p>앱이 두 프로세스로 뜨므로 프로세스 메모리가 아니라 Redis 에 둔다. 키는 정규화한 필터 조합이다 —
 * 파라미터 순서나 등급 배열의 순서 · 중복이 달라도 같은 조합이면 같은 키다.
 *
 * <p>{@code RedisConfig} 의 {@code RedisTemplate<String, Object>} 를 쓰지 않는다. 그 직렬화기는 기본
 * 타이핑이라 final 인 record 최상위 값에 타입 힌트가 붙지 않아 되읽을 때 구체 타입을 알 수 없다.
 * 문자열 템플릿에 JSON 을 넣고 읽을 때 타입을 지정한다.
 *
 * <p>Redis 장애는 조회 실패로 번지지 않게 한다. 캐시를 못 읽거나 못 쓰면 경고만 남기고 DB 집계로 간다.
 */
@Slf4j
@Component
public class DistrictCountCacheStore {

    private static final String KEY_PREFIX = "property:district-counts:";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public DistrictCountCacheStore(
            StringRedisTemplate redis,
            JsonMapper jsonMapper,
            @Value("${property.district-counts.cache-ttl}") Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
    }

    public Optional<DistrictCountsResponse> find(DistrictCountRequest filter) {
        try {
            String json = redis.opsForValue().get(key(filter));
            return json == null
                    ? Optional.empty()
                    : Optional.of(jsonMapper.readValue(json, DistrictCountsResponse.class));
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 읽기 실패 — DB 집계로 진행", e);
            return Optional.empty();
        }
    }

    public void save(DistrictCountRequest filter, DistrictCountsResponse response) {
        try {
            redis.opsForValue().set(key(filter), jsonMapper.writeValueAsString(response), ttl);
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 쓰기 실패", e);
        }
    }

    /** 필터를 고정 순서의 문자열로 정규화한다. 빈 값은 비어 있는 칸으로 둔다. */
    static String key(DistrictCountRequest f) {
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
        return KEY_PREFIX + joiner;
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
