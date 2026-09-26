package com.duri.rentalplatform.config;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 알림 채널 구독을 컨텍스트 기동과 떼어 놓는다(NOTI-03). Redis 가 닿지 않아도 앱이 뜨고, Redis 가 돌아오면 구독이 스스로 맺힌다 —
 * 시스템 구성서 5.2 「Redis 중단 — 조회는 동작, 실시간 알림 불가」가 기동 단계에서도 성립하게 한다.
 *
 * <p><b>왜 컨테이너의 자동 시작에 맡기지 않는가</b> — spring-data-redis 4.1.1 기준.
 * {@code RedisMessageListenerContainer#start()} 는 {@code lazyListen()} 에서 첫 구독 결과를 최대 2초 기다리고, 실패하면
 * 예외를 던진다. 첫 시도의 연결 실패는 복구 백오프를 타지 않는다 — {@code Subscriber#initialize} 가
 * {@code connectionFactory.getConnection()} 의 예외를 {@code InitialBackoffExecution} 이면 그대로 실패로 끝낸다. Lettuce 연결
 * 팩토리의 {@code getConnection()} 은 공유 연결을 그 자리에서 맺으므로 Redis 가 없으면 여기서 {@code RedisConnectionFailureException}
 * 이 난다. 컨테이너는 {@code SmartLifecycle}(자동 시작 기본값 true)이라 이 예외가 {@code DefaultLifecycleProcessor} 에서
 * {@code ApplicationContextException("Failed to start bean ...")} 로 감싸여 컨텍스트 기동을 실패시킨다. 라이브러리의
 * {@code setRecoveryBackoff} 는 구독이 한 번 맺힌 뒤의 끊김에만 걸리므로 이 경로를 막지 못한다.
 *
 * <p><b>그래서</b> 컨테이너의 자동 시작을 끄고 이 빈이 전용 스레드에서 {@code start()} 를 부른다. 실패하면 {@code stop()} 으로
 * 되돌린 뒤 {@link #retryInterval} 뒤에 다시 부른다 — {@code start()} 는 {@code started} 플래그를 먼저 세우므로 되돌리지 않으면
 * 다음 호출이 무시된다. 한 번 맺힌 뒤의 끊김은 라이브러리의 복구(기본 5초 고정 간격, 횟수 무제한)와 Lettuce 재연결이 맡는다.
 *
 * <p><b>구독 전 발행분은 유실된다</b> — 발행 · 구독 채널은 보관하지 않는다. 구독이 맺히기 전 다른 인스턴스가 발행한 알림은 이
 * 인스턴스의 연결에 닿지 않는다. 알림 이력은 DB 에 저장되어 있고, 화면은 재연결 뒤 목록을 재조회해 그 사이 알림을 확인한다 — 아키텍처
 * 설계서(알림 전달) 1.2 「연결이 끊기면 재연결 뒤 목록 재조회」.
 *
 * <p><b>정지 순서</b> — 이 빈은 컨테이너를 생성자 인자로 받아 의존 관계가 등록된다. {@code DefaultLifecycleProcessor#doStop} 은
 * 의존하는 빈을 먼저 멈추므로(spring-context 7.0.9), 재시도가 멈춘 뒤에 컨테이너가 멈춘다. 종료 중에 구독을 다시 여는 일이 없다.
 */
@Slf4j
public class RedisSubscriptionStarter implements SmartLifecycle {

    private final RedisMessageListenerContainer container;
    private final Duration retryInterval;

    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    public RedisSubscriptionStarter(RedisMessageListenerContainer container, Duration retryInterval) {
        this.container = container;
        this.retryInterval = retryInterval;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("redis-subscription-starter").daemon(true).factory());
        running = true;
        executor.execute(this::attempt);
    }

    /**
     * 구독을 한 번 시도한다. {@code start()} 는 구독이 맺히거나 실패할 때까지 이 스레드를 세운다 — 요청 스레드가 아니므로 기다려도 된다.
     *
     * <p>예외 없이 돌아와도 구독 여부를 다시 본다. {@code start()} 는 대기 중 인터럽트를 삼키고 그냥 돌아온다(4.1.1 {@code lazyListen()}).
     */
    private void attempt() {
        if (!running) {
            return;
        }
        String failure;
        try {
            container.start();
            failure = container.isListening() ? null : "구독 대기가 끊겼다";
        } catch (RuntimeException ex) {
            failure = NestedExceptionUtils.getMostSpecificCause(ex).toString();
        }
        if (!running) {
            return;
        }
        if (failure == null) {
            log.info("알림 채널 구독 성립");
            return;
        }
        log.warn("알림 채널 구독 실패 — {}ms 뒤 다시 시도한다: {}", retryInterval.toMillis(), failure);
        resetContainer();
        scheduleRetry();
    }

    /** 실패한 {@code start()} 가 세운 {@code started} 를 내린다. 구독 전 상태면 곧바로 돌아온다. */
    private void resetContainer() {
        try {
            container.stop();
        } catch (RuntimeException ex) {
            log.warn("알림 채널 구독 컨테이너 되돌리기 실패: {}", ex.toString());
        }
    }

    private void scheduleRetry() {
        try {
            executor.schedule(this::attempt, retryInterval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            // 종료 중이다. 다시 시도하지 않는다.
        }
    }

    /** 재시도를 멈춘다. 진행 중인 시도는 인터럽트해 끊는다. 컨테이너 자체는 컨텍스트가 뒤이어 멈춘다. */
    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService current = executor;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            if (!current.awaitTermination(retryInterval.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("알림 채널 구독 시도가 {}ms 안에 끝나지 않았다", retryInterval.toMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
