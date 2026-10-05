package com.duri.rentalplatform.common.security;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시 · 대조의 동시 실행 수를 슬롯마다 묶는 위임 인코더(#408).
 *
 * <p><b>왜</b> — BCrypt 는 일부러 느린 CPU 연산이다. 상한이 없으면 동시 해싱 수가 요청 스레드 수까지 늘어 로그인이 몰릴 때 슬롯 CPU 를
 * 다 쓰고, 같은 슬롯의 다른 요청까지 CPU 를 다투며 느려진다(#390 R05 — 로그인 몰림 p95 3.6 s · 앱 CPU 95 %). 비용을 낮추는 길은 보안
 * 하한(비용 10 이상 — 보안 · 암호화 설계서 4.2)이 막으므로, 비용은 그대로 두고 <b>동시에 도는 수</b>를 묶는다.
 *
 * <p><b>넘치면</b> — 허가를 {@code maxWait} 까지만 기다리고, 못 얻으면 503 {@code SERVICE_BUSY} 다. 무한히 줄 세우면 넘치는 부하가 대기로
 * 쌓여 응답 시간만 늘고 요청 스레드가 묶인다. 클라이언트가 다시 시도한다(API 공통 명세 2장).
 *
 * <p><b>범위</b> — 이 인코더를 쓰는 곳 전부다(가입 · 로그인 · 비밀번호 재설정). {@link #upgradeEncoding} 은 해시 문자열만 보는 가벼운
 * 연산이라 묶지 않는다. 가입되지 않은 이메일의 로그인은 대조를 하지 않지만 {@link #awaitCapacity()} 로 같은 허가를 얻는다 — 아래.
 *
 * <p><b>가입 여부 노출</b> — 상한이 찼을 때 가입된 이메일만 503 을 받고 없는 이메일은 401 을 받으면, 몰린 순간 상태 코드만으로 가입 여부가
 * 갈린다. 그래서 없는 사용자 경로도 {@link #awaitCapacity()} 로 같은 허가를 얻고(해시는 계산하지 않고 곧바로 반납) 포화면 같은 503 을 받는다.
 * 응답 <b>시간</b> 차이(없는 사용자는 대조가 없어 빠르다)는 이것으로 없어지지 않는다 — 기존 동작 그대로다.
 *
 * <p><b>무상태</b> — 허가는 이 프로세스의 CPU 를 지키는 지역 자원이라 공유 저장소에 두지 않는다. 슬롯마다 따로 센다.
 *
 * <p><b>지표</b> — {@value #REJECTED_METRIC}(거절 수) · {@value #WAIT_METRIC}(허가를 얻기까지 기다린 시간 — 얻은 것 · 못 얻은 것 모두).
 * 거절이 늘면 이 상한이 로그인 몰림을 막고 있다는 뜻이고, 대기가 늘면 상한에 닿기 시작했다는 뜻이다.
 */
public class BoundedPasswordEncoder implements PasswordEncoder, MeterBinder {

    public static final String REJECTED_METRIC = "auth.password.hashing.rejected";
    public static final String WAIT_METRIC = "auth.password.hashing.wait";

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final long maxWaitNanos;

    /** 레지스트리가 묶이기 전(단위 테스트 · 지표 없는 슬라이스)에는 null — 세지 않는다. */
    private volatile Counter rejected;
    private volatile Timer waitTimer;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1: " + maxConcurrent);
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative: " + maxWait);
        }
        this.delegate = delegate;
        // 공정(FIFO) — 먼저 기다린 요청이 먼저 얻는다. 비공정이면 막 들어온 요청이 끼어들어 오래 기다린 요청이 대기 상한에 걸린다.
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWaitNanos = maxWait.toNanos();
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withPermit(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withPermit(() -> delegate.matches(rawPassword, encodedPassword));
    }

    /**
     * 해시를 계산하지 않고 허가만 얻었다가 곧바로 반납한다. 포화면 해시 · 대조와 같은 503 {@code SERVICE_BUSY} 다. CPU 를 쓰지 않으므로
     * 허가를 쥐는 시간은 사실상 0 이다 — 상태 코드만 대조 경로와 맞춘다(클래스 주석 「가입 여부 노출」).
     */
    public void awaitCapacity() {
        withPermit(() -> null);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.rejected = Counter.builder(REJECTED_METRIC)
                .description("비밀번호 해시 · 대조 동시 실행 상한에 걸려 503 으로 거절한 수")
                .register(registry);
        this.waitTimer = Timer.builder(WAIT_METRIC)
                .description("비밀번호 해시 · 대조의 실행 허가를 얻기까지 기다린 시간")
                .register(registry);
    }

    private <T> T withPermit(Supplier<T> hashing) {
        long startedAt = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // 종료 중 등으로 끊겼다. 끊김 표시를 되살리고 이 요청은 처리하지 못한 것으로 답한다.
            Thread.currentThread().interrupt();
            acquired = false;
        }
        recordWait(System.nanoTime() - startedAt);
        if (!acquired) {
            Counter counter = rejected;
            if (counter != null) {
                counter.increment();
            }
            throw new BusinessException(ErrorCode.SERVICE_BUSY);
        }
        try {
            return hashing.get();
        } finally {
            permits.release();
        }
    }

    private void recordWait(long nanos) {
        Timer timer = waitTimer;
        if (timer != null) {
            timer.record(nanos, TimeUnit.NANOSECONDS);
        }
    }
}
