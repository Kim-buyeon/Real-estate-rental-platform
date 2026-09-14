package com.duri.rentalplatform.config;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
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
 * <p><b>빈으로 등록하지 않는다</b> — {@code Executor} 빈을 두면 자동 구성의 {@code applicationTaskExecutor} 가 물러난다.
 * {@link AsyncConfigurer} 로 {@code @Async} 에만 넘긴다.
 *
 * <p><b>동시 실행 상한</b> — 두지 않았다. 실제 동시성은 커넥션 풀이 묶는다. 등기 점검 배치가 변동을 한꺼번에 낼 때의 적정 상한은
 * 미확정이며 5주차 부하 시험(T7 배치 동시)에서 확인한다.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("async-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /** 반환값 없는 비동기 메서드의 예외는 호출자에게 가지 않는다. 사라지지 않게 기록한다. */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("비동기 작업 실패 method={}", method.toGenericString(), ex);
    }
}
