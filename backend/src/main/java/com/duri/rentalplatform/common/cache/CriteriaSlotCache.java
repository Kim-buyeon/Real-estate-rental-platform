package com.duri.rentalplatform.common.cache;

import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기준표 슬롯 캐시의 공통 동작 — 슬롯 메모리에 기준표 한 벌(불변 값)을 들고, 비면 DB 에서 읽고, 바뀌면 비운다. 아키텍처 설계서(성능)
 * 1.3 「판정 기준표는 슬롯 메모리에 둔다」. 위험도 기준 캐시와 대출 기준 캐시가 이어받는다.
 *
 * <p><b>요청 경로</b> — {@link #current()} 는 Redis 를 부르지 않는다. 비어 있으면(기동 직후 · 무효화 직후) DB 에서 읽어 채운다.
 * 동시에 비어 있는 것을 본 요청들은 하나만 읽고 나머지는 그 결과를 쓴다.
 *
 * <p><b>무효화</b> — 기준을 바꾸는 쪽이 {@link #invalidateAfterCommit()} 를 부른다. <b>커밋 뒤</b>에 자기 슬롯을 비우고 버전 키를
 * 올린다({@link CriteriaVersionStore}) — 커밋 전에 올리면 다른 슬롯이 옛 값을 다시 읽어 들 수 있다. 다른 슬롯은
 * {@link CriteriaVersionWatcher} 가 버전 키를 보고 다시 읽는다.
 *
 * <p><b>읽는 중의 무효화</b> — 읽기를 시작한 뒤 무효화가 있었으면 읽은 값을 담지 않는다(세대 번호). 커밋 전에 읽은 옛 값일 수 있다.
 *
 * @param <T> 기준표 한 벌. 불변이어야 한다 — 여러 요청 스레드가 함께 읽는다. 엔티티를 담지 않는다(영속성 컨텍스트 밖 지연 로딩)
 */
@Slf4j
public abstract class CriteriaSlotCache<T> {

    private final CriteriaVersionStore versionStore;
    private final TransactionTemplate readTransaction;

    /** 비어 있는 캐시를 한 요청만 채우게 한다. DB 읽기 동안 쥔다. */
    private final ReentrantLock loadLock = new ReentrantLock();
    /** {@link #current} · {@link #generation} 을 함께 바꾼다. 쥐는 동안 I/O 를 하지 않는다. */
    private final ReentrantLock stateLock = new ReentrantLock();

    private volatile T current;
    private long generation;

    protected CriteriaSlotCache(CriteriaVersionStore versionStore, PlatformTransactionManager transactionManager) {
        this.versionStore = versionStore;
        this.readTransaction = new TransactionTemplate(transactionManager);
        // 표 여럿을 커넥션 하나로 읽는다. 읽기 분산 대상이 아니다 — 무효화 직후 standby 에 아직 없는 값을 읽으면 그 값이 다음
        // 무효화까지 남는다. 읽기 전용 표시는 기본 풀로 간다(읽기 분산은 @ReplicaRead 표시로만 고른다).
        this.readTransaction.setReadOnly(true);
    }

    /** 기준표를 DB 에서 읽어 한 벌로 만든다. 읽기 전용 트랜잭션 안에서 불린다. */
    protected abstract T load();

    /** 지금 기준표. Redis 를 부르지 않는다. 비어 있으면 DB 에서 읽어 채운다. 읽기 실패는 그대로 올린다. */
    public T current() {
        T cached = current;
        if (cached != null) {
            return cached;
        }
        loadLock.lock();
        try {
            cached = current;
            if (cached != null) {
                return cached;
            }
            long startedGeneration = generation();
            T loaded = readInTransaction();
            storeUnlessInvalidated(startedGeneration, loaded);
            return loaded;
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * 비우지 않고 다시 읽어 바꾼다 — 안전망 · 버전 변경 뒤 채우기. 읽기가 실패하면 지금 값을 그대로 둔다(비어 있으면 다음 요청이
     * 읽는다).
     */
    public void reload() {
        long startedGeneration = generation();
        T loaded;
        try {
            loaded = readInTransaction();
        } catch (RuntimeException e) {
            log.warn("기준표를 다시 읽지 못했다 — 지금 값을 유지한다. cache={}", getClass().getSimpleName(), e);
            return;
        }
        storeUnlessInvalidated(startedGeneration, loaded);
    }

    /**
     * 기준을 바꾼 트랜잭션이 커밋된 뒤 이 슬롯을 비우고 버전 키를 올린다. 트랜잭션 밖에서 부르면 곧바로 한다. Redis 실패는 경고만
     * 남긴다 — 수정 요청은 이미 커밋됐다.
     */
    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishChange();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishChange();
            }
        });
    }

    /** 이 슬롯을 비운다. 진행 중인 읽기의 결과도 담지 않게 한다. */
    public void invalidate() {
        stateLock.lock();
        try {
            generation++;
            current = null;
        } finally {
            stateLock.unlock();
        }
    }

    private void publishChange() {
        invalidate();
        versionStore.increment();
    }

    private T readInTransaction() {
        return readTransaction.execute(status -> load());
    }

    private long generation() {
        stateLock.lock();
        try {
            return generation;
        } finally {
            stateLock.unlock();
        }
    }

    private void storeUnlessInvalidated(long startedGeneration, T loaded) {
        stateLock.lock();
        try {
            if (generation == startedGeneration) {
                current = loaded;
            }
        } finally {
            stateLock.unlock();
        }
    }
}
