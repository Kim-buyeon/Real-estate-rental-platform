package com.duri.rentalplatform.common.lock;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link DistributedLock} 이 붙은 메서드를 Redis 락 안에서 실행한다. 아키텍처 설계서(횡단 관심사) 1.1 분산 락 · 1.2.
 *
 * <p><b>획득</b> — {@code SET key token NX PX leaseTime}. 만료를 반드시 함께 건다 — 잡은 인스턴스가 해제 전에 죽어도 락이
 * 영구히 남지 않는다. 못 잡으면 {@code pollInterval} 마다 다시 시도하고, {@code waitTimeout} 을 넘으면 503 이다.
 *
 * <p><b>해제</b> — 메서드가 반환하거나 예외를 던진 뒤 토큰을 비교해 지운다. 처리가 만료를 넘겨 다른 요청이 새로 잡았다면 늦게
 * 끝난 쪽이 남의 락을 지우면 안 된다. 비교와 삭제 사이에 끼어들 틈이 없도록 Lua 스크립트 하나로 실행한다. 해제 실패(Redis
 * 장애)는 기록만 하고 올리지 않는다 — 락은 만료로 풀리고, 주 로직의 결과나 예외를 해제 실패가 덮으면 안 된다.
 *
 * <p><b>연장</b> — {@code renewInterval} 을 둔 락만. 획득 뒤 간격마다 토큰이 같을 때만 만료를 {@code leaseTime} 으로 되돌리고
 * (비교와 연장을 Lua 하나로), 메서드가 끝나면 연장을 멈춘 뒤 위처럼 해제한다. 날짜 락이 이것을 쓰는 이유는 해제가 {@code finally}
 * 에 있기 때문이다 — 배포 · OOM 으로 프로세스가 죽으면 {@code finally} 가 돌지 않아, 만료 23시간으로 잡던 날짜 락이 그대로 남았다
 * (2026-10-01 02:15 배포로 끊긴 회차의 락 때문에 08:43 기동 뒤 따라잡기가 「다른 인스턴스가 실행 중」으로 건너뛰었다, #340).
 * 연장하면 만료를 짧게 잡아도 오래 도는 회차 중에 풀리지 않고, 프로세스가 죽으면 연장도 함께 멈춰 짧은 만료 안에 풀린다. 간격은
 * 만료의 1/3 이하로 둔다 — 연장을 두 번 놓쳐도 유지된다. 토큰이 다르면(만료로 풀린 뒤 다른 인스턴스가 잡았다) 경고를 남기고 그
 * 연장만 멈춘다 — 주 로직은 그대로 두고 예외로 덮지 않는다(해제 실패와 같은 원칙). 연장 실패(Redis 장애)도 경고만 남기고 다음
 * 간격에 다시 시도한다. 연장은 전용 데몬 스레드 하나가 맡고, 빈이 내려갈 때 멈춘다.
 *
 * <p><b>순서</b> — 트랜잭션 프록시(가장 낮은 우선순위)보다 바깥에서 돈다. 안쪽이면 커밋 전에 락이 풀려, 다음 요청이 반영 전 값을
 * 읽는다. 다만 {@code HIGHEST_PRECEDENCE} 는 쓰지 않는다 — 스프링이 체인 맨 앞에 두는 {@code ExposeInvocationInterceptor}
 * ({@code HIGHEST_PRECEDENCE + 1}) 보다 앞서면 애노테이션 인자 바인딩이 호출마다 {@code IllegalStateException} 이 된다.
 *
 * <p>문자열 템플릿을 쓰는 이유는 {@code RefreshTokenStore} 와 같다 — 기본 타이핑 직렬화기는 토큰 문자열에 타입 힌트를 붙여
 * 비교가 어긋난다.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DistributedLockAspect implements DisposableBean {

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate stringRedisTemplate;
    private final Environment environment;
    private final ScheduledThreadPoolExecutor renewalExecutor;

    public DistributedLockAspect(StringRedisTemplate stringRedisTemplate, Environment environment) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.environment = environment;
        this.renewalExecutor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "distributed-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
        // 끝난 실행의 연장 작업을 취소하면 큐에서 바로 뺀다 — 남겨 두면 다음 예정 시각까지 쌓인다.
        this.renewalExecutor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void destroy() {
        renewalExecutor.shutdownNow();
    }

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String key = keyOf(joinPoint, distributedLock);
        Duration waitTimeout = durationOf(distributedLock.waitTimeout());
        Duration pollInterval = durationOf(distributedLock.pollInterval());
        Duration leaseTime = durationOf(distributedLock.leaseTime());
        Duration renewInterval = optionalDurationOf(distributedLock.renewInterval());
        if (renewInterval != null && (renewInterval.isNegative() || renewInterval.isZero()
                || renewInterval.compareTo(leaseTime) >= 0)) {
            throw new IllegalStateException("분산 락 연장 간격은 0 보다 크고 만료보다 짧아야 한다: " + renewInterval
                    + " / " + leaseTime + " @ " + key);
        }

        String token = acquire(key, waitTimeout, pollInterval, leaseTime);
        Renewal renewal = renewInterval == null ? null : startRenewal(key, token, leaseTime, renewInterval);
        try {
            return joinPoint.proceed();
        } finally {
            if (renewal != null) {
                renewal.stop();
            }
            release(key, token);
        }
    }

    /** 잡을 때까지 시도한다. 잡으면 해제에 쓸 토큰, 상한을 넘으면 503. */
    private String acquire(String key, Duration waitTimeout, Duration pollInterval, Duration leaseTime) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        while (true) {
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, token, leaseTime))) {
                return token;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
            }
        }
    }

    private Renewal startRenewal(String key, String token, Duration leaseTime, Duration renewInterval) {
        Renewal renewal = new Renewal(key, token, String.valueOf(leaseTime.toMillis()));
        long intervalMillis = renewInterval.toMillis();
        renewal.future = renewalExecutor.scheduleWithFixedDelay(
                renewal, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return renewal;
    }

    private void release(String key, String token) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (RuntimeException e) {
            log.warn("분산 락 해제 실패 — 만료로 풀린다. key={}", key, e);
        }
    }

    private String keyOf(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), PARAMETER_NAMES);
        String key = PARSER.parseExpression(distributedLock.key()).getValue(context, String.class);
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("분산 락 키가 비었다: " + distributedLock.key() + " @ " + method);
        }
        return key;
    }

    /** 자리표시자를 풀고 기간으로 바꾼다. {@code 30s} 와 {@code PT30S} 를 모두 받는다. */
    private Duration durationOf(String value) {
        String resolved = environment.resolveRequiredPlaceholders(value);
        return ApplicationConversionService.getSharedInstance().convert(resolved, Duration.class);
    }

    /** {@link #durationOf} 와 같되, 비어 있으면(풀린 값이 비어도) null — 그 기능을 쓰지 않는다. */
    private Duration optionalDurationOf(String value) {
        String resolved = environment.resolveRequiredPlaceholders(value);
        if (!StringUtils.hasText(resolved)) {
            return null;
        }
        return ApplicationConversionService.getSharedInstance().convert(resolved.trim(), Duration.class);
    }

    /**
     * 실행 하나의 연장 작업. 락을 잃으면 스스로 멈춘다. {@link #stop()} 뒤에 돌던 연장이 해제와 겹쳐 0 을 받아도 경고를 남기지
     * 않는다 — 끝난 실행이 해제한 것이지 잃은 것이 아니다.
     */
    private final class Renewal implements Runnable {

        private final String key;
        private final String token;
        private final String leaseMillis;
        private volatile boolean stopped;
        private volatile ScheduledFuture<?> future;

        private Renewal(String key, String token, String leaseMillis) {
            this.key = key;
            this.token = token;
            this.leaseMillis = leaseMillis;
        }

        @Override
        public void run() {
            if (stopped) {
                stop(); // 예약이 future 를 넘겨받기 전에 멈춘 경우까지 취소한다
                return;
            }
            Long renewed;
            try {
                renewed = stringRedisTemplate.execute(RENEW_SCRIPT, List.of(key), token, leaseMillis);
            } catch (RuntimeException e) {
                log.warn("분산 락 연장 실패 — 다음 간격에 다시 시도한다. key={}", key, e);
                return;
            }
            if (!stopped && (renewed == null || renewed == 0L)) {
                log.warn("분산 락을 잃었다 — 만료로 풀린 뒤 다른 실행이 잡았을 수 있다. 연장을 멈춘다. key={}", key);
                stop();
            }
        }

        void stop() {
            stopped = true;
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
