package com.duri.rentalplatform.common.lock;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
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
public class DistributedLockAspect {

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate stringRedisTemplate;
    private final Environment environment;

    public DistributedLockAspect(StringRedisTemplate stringRedisTemplate, Environment environment) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.environment = environment;
    }

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String key = keyOf(joinPoint, distributedLock);
        Duration waitTimeout = durationOf(distributedLock.waitTimeout());
        Duration pollInterval = durationOf(distributedLock.pollInterval());
        Duration leaseTime = durationOf(distributedLock.leaseTime());

        String token = acquire(key, waitTimeout, pollInterval, leaseTime);
        try {
            return joinPoint.proceed();
        } finally {
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
}
