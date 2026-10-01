package com.duri.rentalplatform.common.datasource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드의 조회를 읽기용 풀(standby 우선)로 보낸다. 동작은 {@link ReplicaReadAspect} 와 {@link ReplicaRoutingDataSource} 가
 * 갖는다.
 *
 * <p><b>효과가 있는 조건</b> — 읽기 분산이 켜져 있을 때만({@code app.datasource.replica.enabled} 가 true 이고 주소가 있을 때,
 * {@link ReplicaRoutingCondition}). 꺼져 있으면 관점 빈이 없어 표시는 아무 일도 하지 않고 모든 조회가 기본 풀로 간다.
 *
 * <p><b>붙이는 곳</b> — 쓰기 직후에 읽지 않는 조회만. standby 는 비동기 복제라 방금 커밋한 값이 아직 없을 수 있다. 사용자가 방금
 * 바꾼 것을 다시 읽는 조회(관심 여부를 담는 상세 등)에는 붙이지 않는다.
 *
 * <p><b>프록시 제약</b> — 스프링 AOP 프록시로 동작한다. 같은 클래스 안에서 부르거나 private 메서드에 붙이면 효과가 없다. 이미
 * 트랜잭션 안에서 부르면 바꾸지 않는다 — 그 트랜잭션의 커넥션이 기본 풀 것이다({@link ReplicaReadAspect}).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReplicaRead {
}
