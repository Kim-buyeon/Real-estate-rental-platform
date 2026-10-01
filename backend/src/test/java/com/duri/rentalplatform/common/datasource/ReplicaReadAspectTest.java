package com.duri.rentalplatform.common.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 표시 구간과 라우팅 키 결정. 외부 의존이 없다. */
class ReplicaReadAspectTest {

    private final ReplicaReadAspect aspect = new ReplicaReadAspect();
    private final ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();

    @AfterEach
    void clean() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        // 누수 확인용 — 어느 테스트가 남겼어도 다음이 오염되지 않게 비운다
        while (ReplicaReadContext.isActive()) {
            ReplicaReadContext.exit(false);
        }
    }

    interface Body {
        Object call() throws Throwable;
    }

    private Object key() {
        return routing.determineCurrentLookupKey();
    }

    private ProceedingJoinPoint joinPoint(Body body) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        when(jp.proceed()).thenAnswer(inv -> body.call());
        return jp;
    }

    @Test
    @DisplayName("표시 밖에서는 키가 primary 다")
    void primaryOutsideMark() {
        assertThat(key()).isEqualTo(ReplicaRoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("표시 구간 안에서는 키가 replica 이고 끝나면 primary 로 돌아온다")
    void replicaInsideThenReleased() throws Throwable {
        List<Object> seen = new ArrayList<>();
        Object result = aspect.route(joinPoint(() -> {
            seen.add(key());
            return "ok";
        }));

        assertThat(result).isEqualTo("ok");
        assertThat(seen).containsExactly(ReplicaRoutingDataSource.REPLICA);
        assertThat(key()).isEqualTo(ReplicaRoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("예외로 끝나도 표시가 풀린다")
    void releasedOnException() {
        assertThatThrownBy(() -> aspect.route(joinPoint(() -> {
            throw new IllegalStateException("boom");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(key()).isEqualTo(ReplicaRoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("중첩된 표시 구간에서 안쪽이 끝나도 바깥의 표시는 남는다")
    void nestedRestoresOuter() throws Throwable {
        List<Object> seen = new ArrayList<>();
        aspect.route(joinPoint(() -> {
            aspect.route(joinPoint(() -> {
                seen.add(key());
                return null;
            }));
            seen.add(key());
            return null;
        }));

        assertThat(seen).containsExactly(ReplicaRoutingDataSource.REPLICA, ReplicaRoutingDataSource.REPLICA);
        assertThat(key()).isEqualTo(ReplicaRoutingDataSource.PRIMARY);
    }

    @Test
    @DisplayName("이미 트랜잭션이 활성이면 표시하지 않고 primary 에 둔다")
    void unchangedInsideTransaction() throws Throwable {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        List<Object> seen = new ArrayList<>();

        aspect.route(joinPoint(() -> {
            seen.add(key());
            return null;
        }));

        assertThat(seen).containsExactly(ReplicaRoutingDataSource.PRIMARY);
    }
}
