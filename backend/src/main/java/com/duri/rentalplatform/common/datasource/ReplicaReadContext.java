package com.duri.rentalplatform.common.datasource;

/**
 * 지금 스레드가 {@link ReplicaRead} 메서드 안에 있는지. {@link ReplicaRoutingDataSource} 가 커넥션을 고를 때 읽는다.
 *
 * <p>스레드에 묶이므로 표시된 메서드 안에서 다른 스레드로 넘긴 일(비동기 실행기 등)은 기본 풀로 간다.
 */
public final class ReplicaReadContext {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private ReplicaReadContext() {
    }

    /** 표시 구간에 들어간다. 들어가기 전 상태를 돌려준다 — {@link #exit(boolean)} 에 그대로 넘긴다. */
    static boolean enter() {
        boolean previous = isActive();
        ACTIVE.set(Boolean.TRUE);
        return previous;
    }

    /**
     * 표시 구간에서 나온다. 바깥이 이미 표시 구간이었으면(중첩) 그대로 두고, 아니면 값을 지운다 — 스레드 풀이 스레드를 재사용하므로
     * {@code false} 로 남기지 않고 지운다.
     */
    static void exit(boolean previous) {
        if (!previous) {
            ACTIVE.remove();
        }
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
