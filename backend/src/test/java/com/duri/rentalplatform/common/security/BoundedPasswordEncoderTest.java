package com.duri.rentalplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link BoundedPasswordEncoder} — 동시 실행 상한 · 대기 초과 503 · 정상 경로 · 허가 반납.
 *
 * <p>BCrypt 대신 멈춰 세울 수 있는 가짜 인코더를 감싼다. 허가를 쥔 호출을 일부러 붙잡아 두어야 「상한에 닿은 상태」를 결정적으로 만들 수
 * 있다. 대기 상한은 짧게(100 ms) 준다 — 테스트가 기다리는 시간이다.
 */
class BoundedPasswordEncoderTest {

    private static final Duration SHORT_WAIT = Duration.ofMillis(100);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("상한 안이면 위임 인코더의 결과를 그대로 돌려주고 허가를 반납한다")
    void delegatesAndReleasesPermit() {
        BlockingEncoder delegate = new BlockingEncoder();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, SHORT_WAIT);

        // 허가 하나로 연달아 두 번 — 첫 호출이 반납하지 않았다면 두 번째가 대기 상한에 걸린다.
        assertThat(encoder.encode("pw")).isEqualTo("hashed:pw");
        assertThat(encoder.matches("pw", "hashed:pw")).isTrue();
        assertThat(encoder.matches("other", "hashed:pw")).isFalse();
        assertThat(delegate.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("허가를 모두 쥔 동안 들어온 호출은 대기 상한 뒤 503 SERVICE_BUSY 이고 위임 인코더를 부르지 않는다")
    void rejectsWithServiceBusyWhenSaturated() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.blockNext();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, SHORT_WAIT);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        encoder.bindTo(registry);

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> encoder.encode("first"), executor);
        delegate.awaitEntered();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> encoder.matches("second", "hashed:second"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_BUSY);
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // 대기 상한만큼은 기다렸다 — 곧바로 거절하지 않는다.
        assertThat(waitedMillis).isGreaterThanOrEqualTo(SHORT_WAIT.toMillis() - 5);
        // 거절된 호출은 위임 인코더에 닿지 않았다 — 쥐고 있는 첫 호출 하나뿐이다.
        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(registry.get(BoundedPasswordEncoder.REJECTED_METRIC).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(BoundedPasswordEncoder.WAIT_METRIC).timer().count()).isEqualTo(2L);

        delegate.release();
        assertThat(holder.get(1, TimeUnit.SECONDS)).isEqualTo("hashed:first");
    }

    @Test
    @DisplayName("대기 상한 안에 허가가 반납되면 기다린 호출이 이어서 처리된다")
    void waitingCallProceedsWhenPermitReleased() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.blockNext();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, Duration.ofSeconds(2));

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> encoder.encode("first"), executor);
        delegate.awaitEntered();
        CompletableFuture<Boolean> waiter =
                CompletableFuture.supplyAsync(() -> encoder.matches("second", "hashed:second"), executor);

        delegate.release();

        assertThat(holder.get(1, TimeUnit.SECONDS)).isEqualTo("hashed:first");
        assertThat(waiter.get(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("상한 수만큼은 동시에 들어가고 그 다음 호출부터 막힌다")
    void admitsUpToMaxConcurrent() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.blockNext();
        delegate.blockNext();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 2, SHORT_WAIT);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> encoder.encode("a"), executor);
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> encoder.encode("b"), executor);
        delegate.awaitEntered();
        delegate.awaitEntered();

        assertThatThrownBy(() -> encoder.encode("c"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_BUSY);

        delegate.release();
        delegate.release();
        assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("hashed:a");
        assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("hashed:b");
    }

    @Test
    @DisplayName("위임 인코더가 예외를 던져도 허가를 반납한다")
    void releasesPermitOnDelegateFailure() {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.failNext = true;
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, SHORT_WAIT);

        assertThatThrownBy(() -> encoder.encode("pw")).isInstanceOf(IllegalStateException.class);
        // 반납하지 않았다면 허가 하나가 영영 빠져 여기서 503 이 난다.
        assertThat(encoder.encode("pw")).isEqualTo("hashed:pw");
    }

    @Test
    @DisplayName("허가만 얻는 호출 — 여유가 있으면 위임 인코더를 부르지 않고 곧바로 반납하고, 포화면 해시 · 대조와 같은 503 이다")
    void awaitCapacityUsesSamePermitsWithoutHashing() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, SHORT_WAIT);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        encoder.bindTo(registry);

        // 여유 — 해시 없이 끝나고 허가를 반납한다(반납하지 않았다면 이어지는 encode 가 막힌다).
        encoder.awaitCapacity();
        assertThat(delegate.calls.get()).isZero();

        delegate.blockNext();
        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> encoder.encode("first"), executor);
        delegate.awaitEntered();

        // 포화 — 가입된 이메일의 대조와 같은 상태 코드다.
        assertThatThrownBy(encoder::awaitCapacity)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_BUSY);
        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(registry.get(BoundedPasswordEncoder.REJECTED_METRIC).counter().count()).isEqualTo(1.0);

        delegate.release();
        assertThat(holder.get(1, TimeUnit.SECONDS)).isEqualTo("hashed:first");
    }

    @Test
    @DisplayName("해시 갱신 필요 여부는 상한을 거치지 않는다 — 해시 문자열만 보는 가벼운 연산이다")
    void upgradeEncodingIsNotBounded() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.blockNext();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, SHORT_WAIT);

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> encoder.encode("first"), executor);
        delegate.awaitEntered();

        assertThat(encoder.upgradeEncoding("hashed:first")).isTrue();

        delegate.release();
        holder.get(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("지표 레지스트리가 묶이지 않아도 거절은 503 으로 동작한다")
    void rejectsWithoutMeterRegistry() throws Exception {
        BlockingEncoder delegate = new BlockingEncoder();
        delegate.blockNext();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(delegate, 1, Duration.ZERO);

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> encoder.encode("first"), executor);
        delegate.awaitEntered();

        assertThatThrownBy(() -> encoder.encode("second"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_BUSY);

        delegate.release();
        holder.get(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("상한이 1 미만이거나 대기 상한이 음수면 만들지 않는다")
    void rejectsInvalidSettings() {
        PasswordEncoder delegate = new BlockingEncoder();

        assertThatThrownBy(() -> new BoundedPasswordEncoder(delegate, 0, SHORT_WAIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedPasswordEncoder(delegate, 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 멈춰 세울 수 있는 가짜 인코더. {@link #blockNext()} 를 부른 횟수만큼 다음 호출들이 들어와 {@link #release()} 를 기다린다.
     * 해시는 {@code hashed:} 접두사를 붙인 문자열이다.
     */
    private static final class BlockingEncoder implements PasswordEncoder {

        final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger blocksRemaining = new AtomicInteger();
        private final Semaphore entered = new Semaphore(0);
        private final Semaphore gate = new Semaphore(0);
        volatile boolean failNext;

        void blockNext() {
            blocksRemaining.incrementAndGet();
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.tryAcquire(1, TimeUnit.SECONDS)).as("blocked call entered the delegate").isTrue();
        }

        void release() {
            gate.release();
        }

        @Override
        public String encode(CharSequence rawPassword) {
            enter();
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            enter();
            return ("hashed:" + rawPassword).equals(encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return true;
        }

        private void enter() {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("delegate failure");
            }
            if (blocksRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                entered.release();
                try {
                    if (!gate.tryAcquire(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
