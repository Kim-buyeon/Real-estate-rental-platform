package com.duri.rentalplatform.common.cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 버전 키를 읽어 바뀌었으면 이 슬롯의 기준표 캐시를 모두 비우고 다시 읽는다. 아키텍처 설계서(성능) 1.3.
 *
 * <p>부르는 곳은 둘이다.
 * <ul>
 *   <li>반복 작업 — 설정 간격({@code risk.criteria-cache.version-check-interval})마다 {@link #reloadIfVersionChanged()}.
 *       다른 슬롯의 기준 변경이 이 슬롯에 닿는 길이다</li>
 *   <li>요청 — 저장된 판정의 기준 지문이 지금 캐시의 지문과 다를 때 {@link #reloadIfVersionChangedThrottled()}. 다른 슬롯이 방금
 *       바꾼 기준으로 판정해 적은 근거를, 아직 옛 기준을 든 이 슬롯이 「기준이 다르다」며 옛 기준으로 다시 판정해 덮는 왕복을 줄인다.
 *       같은 간격에 한 번만 Redis 를 부르고(슬롯당), 다른 확인이 진행 중이면 기다리지 않는다. 요청 경로에 Redis 가 끼는 것은 이
 *       경우뿐이다</li>
 * </ul>
 *
 * <p>버전 키 읽기가 실패하면 아무것도 하지 않는다 — 다른 슬롯의 변경은 안전망({@link #reloadAll()})이 따라온다. 같은 경고를 1초마다
 * 쌓지 않으려고 실패가 시작될 때와 회복될 때만 기록한다.
 *
 * <p>기동 뒤 첫 확인은 키가 있으면 늘 다르다고 보아 한 번 다시 읽는다 — 기동과 첫 확인 사이에 다른 슬롯이 기준을 바꿨을 수 있다.
 */
@Slf4j
@Component
public class CriteriaVersionWatcher {

    private final CriteriaVersionStore versionStore;
    private final List<CriteriaSlotCache<?>> caches;
    private final long requestCheckIntervalNanos;

    /** 확인 하나만 버전 키를 읽고 캐시를 다시 읽게 한다. */
    private final ReentrantLock checkLock = new ReentrantLock();
    /** 마지막으로 본 버전 키 값. {@link #checkLock} 을 쥔 쪽만 쓴다. */
    private String seenVersion;
    private volatile boolean versionReadFailing;
    /** 요청 경로가 마지막으로 확인을 시도한 시각(nanoTime). 처음엔 간격만큼 이전으로 둬 첫 요청은 바로 확인한다. */
    private final AtomicLong lastRequestCheckNanos;

    public CriteriaVersionWatcher(
            CriteriaVersionStore versionStore,
            List<CriteriaSlotCache<?>> caches,
            @Value("${risk.criteria-cache.version-check-interval}") Duration versionCheckInterval) {
        this.versionStore = versionStore;
        this.caches = List.copyOf(caches);
        this.requestCheckIntervalNanos = versionCheckInterval.toNanos();
        this.lastRequestCheckNanos = new AtomicLong(System.nanoTime() - requestCheckIntervalNanos);
    }

    /** 반복 작업용. 다른 확인이 진행 중이면 끝나기를 기다린 뒤 다시 본다. */
    public void reloadIfVersionChanged() {
        checkLock.lock();
        try {
            reloadIfChangedLocked();
        } finally {
            checkLock.unlock();
        }
    }

    /**
     * 요청 경로용. 슬롯당 확인 간격에 한 번만 버전 키를 읽고, 다른 확인이 진행 중이면 기다리지 않는다.
     *
     * @return 버전이 바뀌어 캐시를 다시 읽었으면 참
     */
    public boolean reloadIfVersionChangedThrottled() {
        long now = System.nanoTime();
        long last = lastRequestCheckNanos.get();
        if (now - last < requestCheckIntervalNanos || !lastRequestCheckNanos.compareAndSet(last, now)) {
            return false;
        }
        if (!checkLock.tryLock()) {
            return false;
        }
        try {
            return reloadIfChangedLocked();
        } finally {
            checkLock.unlock();
        }
    }

    /** 버전 키와 무관하게 모두 다시 읽는다 — 버전 키를 못 올린 변경(Redis 장애 중 수정)의 안전망. */
    public void reloadAll() {
        caches.forEach(CriteriaSlotCache::reload);
    }

    private boolean reloadIfChangedLocked() {
        String version;
        try {
            version = versionStore.read();
        } catch (RuntimeException e) {
            if (!versionReadFailing) {
                versionReadFailing = true;
                log.warn("기준표 버전 키를 읽지 못했다 — 회복될 때까지 이 경고를 다시 남기지 않는다. 그동안 다른 슬롯의 기준 변경은 "
                        + "안전망 주기로 따라온다", e);
            }
            return false;
        }
        if (versionReadFailing) {
            versionReadFailing = false;
            log.info("기준표 버전 키 읽기가 회복됐다");
        }
        if (Objects.equals(version, seenVersion)) {
            return false;
        }
        seenVersion = version;
        caches.forEach(cache -> {
            cache.invalidate();
            cache.reload();
        });
        return true;
    }
}
