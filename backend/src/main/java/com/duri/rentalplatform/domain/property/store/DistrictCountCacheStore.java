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
 * 자치구 집계의 Redis 층 — 집계 응답과 세대 키 {@value #GENERATION_KEY} 의 읽기 · 쓰기만 한다. 슬롯 로컬 층 · 세대 관리 · 같은 키
 * 동시 빗나감 합치기는 {@code DistrictCountCache}(cache 패키지)가 갖는다. API 명세서(매물) 1.5 「동일한 필터 조합에 대한 응답은
 * 캐싱」.
 *
 * <p>키는 세대 + 정규화한 필터 조합이다 — 파라미터 순서나 등급 배열의 순서 · 중복이 달라도 같은 조합이면 같은 키다. 세대가 바뀌면
 * 옛 세대의 키는 읽히지 않고 TTL 로 사라진다.
 *
 * <p>{@code RedisConfig} 의 {@code RedisTemplate<String, Object>} 를 쓰지 않는다. 그 직렬화기는 기본 타이핑이라 final 인 record
 * 최상위 값에 타입 힌트가 붙지 않아 되읽을 때 구체 타입을 알 수 없다. 문자열 템플릿에 JSON 을 넣고 읽을 때 타입을 지정한다.
 *
 * <p>집계 응답의 읽기 · 쓰기 실패는 경고만 남긴다 — 조회 실패로 번지지 않게 한다. 세대 키의 읽기 · 올리기 실패는 그대로 올린다 —
 * 부르는 쪽이 경우마다 다르게 다룬다.
 */
@Slf4j
@Component
public class DistrictCountCacheStore {

    public static final String GENERATION_KEY = "property:district-counts:gen";

    /** 세대 키가 없을 때의 세대. 키가 지워져도 이 값으로 이어 간다. */
    public static final String INITIAL_GENERATION = "0";

    private static final String KEY_PREFIX = "property:district-counts:";

    /** 등급 배열이 null 원소뿐일 때의 키 칸. 등급 상수명과 겹치지 않는다. */
    private static final String NULL_GRADES = "(null)";

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

    /** Redis TTL. 로컬 층이 항목 수명의 상한을 계산할 때 쓴다. */
    public Duration ttl() {
        return ttl;
    }

    /** 세대의 집계. 없거나 읽지 못하면 빈 값 — 실패는 경고만 남긴다. */
    public Optional<DistrictCountsResponse> find(String generation, DistrictCountRequest filter) {
        try {
            String json = redis.opsForValue().get(key(generation, filter));
            return json == null
                    ? Optional.empty()
                    : Optional.of(jsonMapper.readValue(json, DistrictCountsResponse.class));
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 읽기 실패 — DB 집계로 진행", e);
            return Optional.empty();
        }
    }

    /** 세대의 집계를 TTL 로 쓴다. 실패는 경고만 남긴다. */
    public void save(String generation, DistrictCountRequest filter, DistrictCountsResponse response) {
        try {
            redis.opsForValue().set(key(generation, filter), jsonMapper.writeValueAsString(response), ttl);
        } catch (RuntimeException e) {
            log.warn("자치구 집계 캐시 쓰기 실패", e);
        }
    }

    /**
     * 지금 세대. 키가 없으면 {@link #INITIAL_GENERATION}.
     *
     * @throws RuntimeException Redis 를 읽지 못했을 때
     */
    public String readGeneration() {
        String value = redis.opsForValue().get(GENERATION_KEY);
        return value == null ? INITIAL_GENERATION : value;
    }

    /**
     * 세대를 올린다.
     *
     * @return 올린 뒤의 세대. Redis 가 값을 돌려주지 않으면 null
     * @throws RuntimeException Redis 에 쓰지 못했을 때
     */
    public String incrementGeneration() {
        Long next = redis.opsForValue().increment(GENERATION_KEY);
        return next == null ? null : next.toString();
    }

    /** 세대의 집계 키. */
    public static String key(String generation, DistrictCountRequest filter) {
        return KEY_PREFIX + "g" + generation + ":" + filterKey(filter);
    }

    /**
     * 필터를 고정 순서의 문자열로 정규화한다. 빈 값은 비어 있는 칸으로 둔다. <b>조회 결과가 같은 요청만 같은 키가 되게 한다</b> — 키가
     * 같으면 한 요청의 응답을 다른 요청이 받는다.
     * <ul>
     *   <li>자치구는 받은 그대로 둔다 — 조회 조건(매퍼 XML)이 앞뒤 공백을 자르지 않아 {@code " 강남구"} 는 0건이다. 잘라서 키를
     *       만들면 {@code "강남구"} 와 응답을 나눠 갖는다. 빈 문자열은 조회 조건에서도 필터 없음이라 null 과 같은 칸이다</li>
     *   <li>등급은 정렬 · 중복 제거하고 null 원소를 뺀다 — 조회 조건 {@code IN (...)} 에서 NULL 은 아무것과도 맞지 않는다. 단 원소가
     *       모두 null 이면 조회 조건은 {@code IN (NULL)}(0건)이라 필터 없음(빈 칸)과 다른 {@value #NULL_GRADES} 로 둔다</li>
     *   <li>면적은 끝자리 0 을 뗀다 — 40 과 40.0 은 같은 조건이다</li>
     * </ul>
     */
    public static String filterKey(DistrictCountRequest f) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add("district=" + str(f.district()));
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
        if (grades == null || grades.isEmpty()) {
            return "";
        }
        TreeSet<RiskGrade> sorted = new TreeSet<>();
        grades.stream().filter(Objects::nonNull).forEach(sorted::add);
        if (sorted.isEmpty()) {
            return NULL_GRADES;
        }
        StringJoiner joiner = new StringJoiner(",");
        sorted.forEach(g -> joiner.add(g.name()));
        return joiner.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
