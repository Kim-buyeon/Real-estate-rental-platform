package com.duri.rentalplatform.domain.notification.store;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 알림 중복 방지 키 — 아키텍처 설계서(알림 전달) 1.1 「동일 사용자 · 동일 대상 · 동일 유형의 알림은 캐시 기반 키로 차단」.
 *
 * <p>키는 {@code noti:dedup:{userId}:{type}:{propertyId}:{afterValue}} 다. 변동 후 값을 넣는 이유 — 유형 · 매물만으로 막으면
 * 하루 안에 SAFE → CAUTION → DANGER 로 두 번 바뀔 때 두 번째 알림이 막힌다. 같은 변동이 재분석 · 배치에서 겹쳐 들어오는 것만
 * 막는다.
 *
 * <p>두 인스턴스가 같은 키를 봐야 하므로 Redis 에 둔다. 값은 쓰지 않고 키의 존재와 만료만 쓴다. 문자열 템플릿을 쓰는 이유는
 * {@code RefreshTokenStore} 와 같다.
 */
@Component
public class NotificationDedupStore {

    private static final String KEY_PREFIX = "noti:dedup:";

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration ttl;

    public NotificationDedupStore(
            StringRedisTemplate stringRedisTemplate,
            @Value("${notification.dedup-ttl}") Duration ttl) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ttl = ttl;
    }

    /**
     * 키를 만료와 함께 건다({@code SET NX PX}).
     *
     * @return 이번에 걸었으면 true, 이미 있으면(같은 알림을 만들었으면) false
     */
    public boolean claim(Long userId, NotificationType type, Long propertyId, String afterValue) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(keyOf(userId, type, propertyId, afterValue), "1", ttl));
    }

    /** 건 키를 푼다 — 알림 저장이 롤백되어 알림이 없는데 키만 남아 다음 알림을 막지 않게 한다. */
    public void release(Long userId, NotificationType type, Long propertyId, String afterValue) {
        stringRedisTemplate.delete(keyOf(userId, type, propertyId, afterValue));
    }

    static String keyOf(Long userId, NotificationType type, Long propertyId, String afterValue) {
        return KEY_PREFIX + userId + ":" + type.name() + ":" + propertyId + ":" + afterValue;
    }
}
