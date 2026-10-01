package com.duri.rentalplatform.common.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 실행을 Redis 분산 락 안에 둔다. 앱이 두 프로세스로 뜨므로 같은 키의 실행이 인스턴스를 넘어 하나만 돈다.
 * 동작은 {@link DistributedLockAspect} 가 갖는다.
 *
 * <p><b>프록시 제약</b> — 스프링 AOP 프록시로 동작한다. 같은 클래스 안에서 부르거나 private 메서드에 붙이면 락이 걸리지
 * 않는다. 락을 걸 지점은 별도 빈의 public 메서드로 둔다.
 *
 * <p><b>시간 속성</b> — 네 값 모두 문자열이며 설정 키 자리표시자({@code "${risk.reanalyze.lock-wait-timeout}"}) 또는 값
 * ({@code "30s"} · {@code "PT30S"} · {@code "0s"})을 받는다. 호출하는 쪽마다 대기 방식이 다르므로(사용자 요청은 기다리고,
 * 배치는 기다리지 않고 건너뛴다) 애노테이션마다 정한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 락 키. SpEL 이며 메서드 인자를 {@code #인자이름} 으로 읽는다 — 예: {@code "'risk:analysis:lock:' + #propertyId"}.
     * 평가 결과가 비어 있으면 실행하지 않고 실패한다.
     */
    String key();

    /**
     * 락을 못 잡았을 때 기다리는 상한. 넘으면 {@code EXTERNAL_API_UNAVAILABLE}(503). {@code 0s} 면 한 번만 시도한다.
     */
    String waitTimeout();

    /** 락이 풀렸는지 다시 시도하는 간격. 대기 상한이 0 이면 쓰이지 않는다. */
    String pollInterval() default "200ms";

    /**
     * 락 만료. 잡은 인스턴스가 해제 전에 죽어도 이 시간이 지나면 풀린다. 메서드 수행 시간보다 짧으면 실행 중에 풀려 다른
     * 요청이 들어온다. {@link #renewInterval()} 을 두면 실행하는 동안 이 길이로 다시 늘리므로 수행 시간과 무관해진다.
     */
    String leaseTime();

    /**
     * 실행하는 동안 만료를 연장하는 간격. 비면(기본) 연장하지 않는다. 두면 간격마다 만료를 {@link #leaseTime()} 으로 되돌린다 —
     * 만료를 짧게 잡고도 오래 도는 실행 중에 풀리지 않고, 잡은 프로세스가 죽으면 연장이 멈춰 짧은 만료 안에 풀린다. 만료보다
     * 짧아야 하며 만료의 1/3 이하로 둔다 — 연장은 앞 연장이 끝난 뒤 간격을 세므로(고정 지연) 마지막 성공에서 다음 시도까지
     * 간격 + 수행 시간이 걸린다. 1/3 이면 한 번 놓쳐도 유지되고, 두 번 놓치면 풀린다. 동작은 {@link DistributedLockAspect} 「연장」.
     */
    String renewInterval() default "";
}
