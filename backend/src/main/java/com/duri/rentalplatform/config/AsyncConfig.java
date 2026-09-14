package com.duri.rentalplatform.config;

import java.time.Duration;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 실행 — 알림 생성 · 발송을 판정 요청의 응답 시간에서 떼어낸다. 아키텍처 설계서(성능) 1.1 「알림 발송 지연」.
 *
 * <p><b>가상 스레드</b> — 기술 스택 정의서가 Java 21 가상 스레드를 채택했다. 비동기 작업은 DB · Redis 대기가 대부분이라 풀
 * 크기를 정할 이유가 없다. 다만 앱 전역 설정({@code spring.threads.virtual.enabled})은 켜지 않았다 — 요청 처리 스레드까지
 * 바뀌므로 이 변경의 범위를 넘는다. 그래서 {@code @Async} 가 쓰는 실행기만 여기서 가상 스레드로 둔다.
 *
 * <p><b>빈으로 등록하지 않는다</b> — {@code Executor} 빈을 두면 자동 구성의 {@code applicationTaskExecutor} 가 물러난다
 * ({@code TaskExecutorConfigurations} 의 {@code @ConditionalOnMissingBean(Executor.class)}, spring-boot-autoconfigure 4.1.1).
 * {@link AsyncConfigurer} 로 {@code @Async} 에만 넘긴다.
 *
 * <p><b>동시 실행 상한</b> — 두지 않았다. {@link SimpleAsyncTaskExecutor#setConcurrencyLimit} 은 상한에 닿으면 제출 스레드를
 * 시간 제한 없이 세운다(spring-core 7.0.9 {@code ConcurrencyThrottleSupport#onLimitReached} 의 {@code Condition.await()}).
 * 알림 생성 작업이 이 실행기의 슬롯을 쥔 채 커밋 후 단계에서 발송({@code NotificationDispatcher#dispatch})을 같은 실행기에
 * 제출하므로, 슬롯이 모두 생성 작업이면 서로를 기다려 멈춘다 — 생성 락 · 커넥션도 쥔 채다. 적정 상한과 실행기 분리 여부는
 * 미확정이며 5주차 부하 시험(T7 배치 동시)에서 확인한다.
 *
 * <p><b>종료 대기</b> — {@code async.termination-timeout}. 배포로 앱이 내려갈 때 커밋 후 생성 중인 알림이 끊기면 「이력 저장이
 * 성공 기준」(알림 전달 설계서 1.1)이 깨진다. {@link SimpleAsyncTaskExecutor#setTaskTerminationTimeout} 의 대기는
 * {@link SimpleAsyncTaskExecutor#close()} 에서만 일어나는데(spring-core 7.0.9), 빈이 아니면 컨텍스트가 {@code close()} 를
 * 부르지 않는다. 그래서 이 설정이 {@link SmartLifecycle} 로 종료 단계에서 직접 닫는다. 단계는 웹 서버 정지
 * ({@code WebServerStartStopLifecycle}, {@code Integer.MAX_VALUE - 2048})보다 낮게 둔다 — 정지는 단계가 높은 쪽부터라 요청이 더
 * 들어오지 않게 된 뒤에 기다리고, 빈 파기(커넥션 풀 · Redis 종료)보다는 앞이라 기다리는 동안 저장이 가능하다.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer, SmartLifecycle {

    /** 웹 서버 정지 뒤, 빈 파기 전. */
    private static final int SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final SimpleAsyncTaskExecutor executor;
    private volatile boolean running;

    public AsyncConfig(@Value("${async.termination-timeout}") Duration terminationTimeout) {
        this.executor = new SimpleAsyncTaskExecutor("async-");
        this.executor.setVirtualThreads(true);
        this.executor.setTaskTerminationTimeout(terminationTimeout.toMillis());
    }

    @Override
    public Executor getAsyncExecutor() {
        return executor;
    }

    /** 반환값 없는 비동기 메서드의 예외는 호출자에게 가지 않는다. 사라지지 않게 기록한다. */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("비동기 작업 실패 method={}", method.toGenericString(), ex);
    }

    @Override
    public void start() {
        running = true;
    }

    /** 실행 중인 작업이 끝나거나 종료 대기가 지날 때까지 막는다. 닫힌 뒤의 제출은 거절된다. */
    @Override
    public void stop() {
        executor.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }
}
