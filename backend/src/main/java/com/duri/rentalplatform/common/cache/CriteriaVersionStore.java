package com.duri.rentalplatform.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 기준표 버전 키 {@value #VERSION_KEY} — 슬롯 캐시({@link CriteriaSlotCache})들이 다른 슬롯의 기준 변경을 알아채는 Redis 값.
 * 아키텍처 설계서(성능) 1.3.
 *
 * <p>위험도 기준 캐시와 대출 기준 캐시가 키 하나를 함께 쓴다. 어느 쪽 기준이 바뀌어도 올리고, 바뀌면 두 캐시 모두 다시 읽는다 — 키를
 * 나누면 확인도 둘이 되는데, 기준 변경이 드물어 한쪽만 다시 읽어 아낄 것이 없다.
 *
 * <p>값은 정수 문자열이고 뜻은 없다 — 바뀌었는지만 본다. 만료를 걸지 않는다.
 */
@Slf4j
@Component
public class CriteriaVersionStore {

    public static final String VERSION_KEY = "criteria:version";

    private final StringRedisTemplate redis;

    public CriteriaVersionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 지금 버전. 키가 없으면 null.
     *
     * @throws RuntimeException Redis 를 읽지 못했을 때 — 부르는 쪽이 「모름」으로 다룬다
     */
    public String read() {
        return redis.opsForValue().get(VERSION_KEY);
    }

    /** 버전을 올린다. 실패하면 경고만 남긴다 — 기준 변경은 이미 커밋됐고, 다른 슬롯은 안전망 주기 안에 따라온다. */
    public void increment() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("기준표 버전 키를 올리지 못했다 — 다른 슬롯은 안전망 주기 안에 바뀐 기준을 읽는다", e);
        }
    }
}
