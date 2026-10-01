package com.duri.rentalplatform.common.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ReplicaRead} 가 붙은 메서드가 도는 동안 커넥션을 읽기용 풀에서 고르게 한다. 읽기 분산이 켜졌을 때만 빈이 된다
 * ({@link ReplicaRoutingCondition}) — 꺼져 있으면 표시는 아무 일도 하지 않는다.
 *
 * <p><b>순서</b> — 트랜잭션 프록시(가장 낮은 우선순위)보다 바깥에서 돈다. 안쪽이면 트랜잭션이 이미 시작된 뒤라 아래의 「이미
 * 트랜잭션 안」 판정이 언제나 참이 되어 바꾸지 못한다. 분산 락 관점({@code HIGHEST_PRECEDENCE + 10})보다는 안쪽이다 — 둘이 한
 * 메서드에 겹칠 일은 지금 없고, 겹쳐도 락은 Redis 라 커넥션 선택과 무관하다. {@code HIGHEST_PRECEDENCE} 를 쓰지 않는 이유는
 * 분산 락 관점의 순서 주석과 같다({@code ExposeInvocationInterceptor} 보다 앞서면 안 된다).
 *
 * <p><b>이미 트랜잭션 안이면 바꾸지 않는다</b> — 바깥 트랜잭션이 아직 커넥션을 고르지 않은 상태에서 읽기용으로 바꾸면, 그
 * 트랜잭션의 커넥션 전체가 읽기 전용 풀 것이 되어 뒤이은 쓰기가 실패한다. 이미 골랐다면 바꿔도 소용이 없다. 어느 쪽이든 기본
 * 풀에 둔다.
 *
 * <p><b>중첩</b> — 들어가기 전 상태를 기억했다가 {@code finally} 에서 되돌린다. 표시된 메서드가 표시된 메서드를 불러도 안쪽이
 * 끝날 때 바깥의 표시가 지워지지 않는다.
 */
@Aspect
@Component
@Conditional(ReplicaRoutingCondition.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ReplicaReadAspect {

    @Around("@annotation(com.duri.rentalplatform.common.datasource.ReplicaRead)")
    public Object route(ProceedingJoinPoint joinPoint) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return joinPoint.proceed();
        }
        boolean previous = ReplicaReadContext.enter();
        try {
            return joinPoint.proceed();
        } finally {
            ReplicaReadContext.exit(previous);
        }
    }
}
