package com.duri.rentalplatform.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link DistributedLockAspect} 를 실제 Redis 에 대고 확인한다 — 획득과 만료, 경합 대기, 대기 초과 503, 토큰 비교 해제(Lua),
 * 예외 시 해제.
 *
 * <p>목으로는 {@code SET NX PX} 의 배타성, 스크립트 문법 · 반환 타입, 프록시로 관점이 실제로 끼는지를 확인할 수 없다. 대상은
 * 테스트 전용 빈이다 — 도메인 빈을 쓰면 락과 무관한 저장소 · 외부 연동이 끌려온다. 컨테이너는
 * {@link TestcontainersConfiguration} 의 싱글턴을 공유한다.
 */
@Tag("integration")
@SpringBootTest
@Import({TestcontainersConfiguration.class, DistributedLockAspectTest.TargetConfig.class})
class DistributedLockAspectTest {

    private static final long ID = 900_001L;
    private static final String KEY = "test:lock:" + ID;

    @Autowired
    LockedTarget target;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clear() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("비어 있으면 잡고 실행한다 — 실행 중에는 키에 만료 이내 TTL 이 걸려 있고, 반환 뒤에는 풀린다")
    void acquiresAndReleases() {
        AtomicReference<String> heldToken = new AtomicReference<>();
        AtomicReference<Long> heldTtl = new AtomicReference<>();

        String result = target.shortWait(ID, () -> {
            heldToken.set(stringRedisTemplate.opsForValue().get(KEY));
            heldTtl.set(stringRedisTemplate.getExpire(KEY, TimeUnit.MILLISECONDS));
        });

        assertThat(result).isEqualTo("done");
        assertThat(heldToken.get()).isNotBlank();
        assertThat(heldTtl.get()).isPositive().isLessThanOrEqualTo(5_000L);
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("다른 쪽이 잡고 있으면 풀릴 때까지 기다렸다가 잡고 실행한다")
    void waitsForReleaseThenProceeds() throws Exception {
        stringRedisTemplate.opsForValue().set(KEY, "other");
        AtomicBoolean ran = new AtomicBoolean(false);

        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> target.longWait(ID, () -> ran.set(true)));
        Thread.sleep(300);
        assertThat(ran).isFalse();

        stringRedisTemplate.delete(KEY);

        assertThat(call.get(3, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(ran).isTrue();
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("대기 상한을 넘기면 503 EXTERNAL_API_UNAVAILABLE — 메서드를 실행하지 않고 남의 락도 건드리지 않는다")
    void waitTimeoutThrows503() {
        stringRedisTemplate.opsForValue().set(KEY, "other");
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatThrownBy(() -> target.shortWait(ID, () -> ran.set(true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        assertThat(ran).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(KEY)).isEqualTo("other");
    }

    @Test
    @DisplayName("실행 중 만료로 다른 쪽이 새로 잡았으면, 끝난 쪽의 해제는 그 락을 지우지 않는다")
    void doesNotReleaseOthersToken() {
        target.shortWait(ID, () -> stringRedisTemplate.opsForValue().set(KEY, "other"));

        assertThat(stringRedisTemplate.opsForValue().get(KEY)).isEqualTo("other");
    }

    @Test
    @DisplayName("메서드가 예외를 던져도 락을 풀고 예외는 그대로 올린다")
    void releasesOnException() {
        assertThatThrownBy(() -> target.shortWait(ID, () -> {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @TestConfiguration
    static class TargetConfig {

        @Bean
        LockedTarget lockedTarget() {
            return new LockedTarget();
        }
    }

    /** 락이 걸리는 테스트 전용 빈. 관점은 프록시로 끼므로 빈으로 등록해 주입받아 부른다. */
    static class LockedTarget {

        @DistributedLock(key = "'test:lock:' + #id", waitTimeout = "200ms", pollInterval = "20ms", leaseTime = "5s")
        public String shortWait(Long id, Runnable body) {
            body.run();
            return "done";
        }

        @DistributedLock(key = "'test:lock:' + #id", waitTimeout = "3s", pollInterval = "20ms", leaseTime = "5s")
        public String longWait(Long id, Runnable body) {
            body.run();
            return "done";
        }
    }
}
