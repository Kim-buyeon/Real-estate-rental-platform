package com.duri.rentalplatform.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link CriteriaVersionWatcher} 의 버전 확인 — 다시 읽을지 말지, 요청 경로의 조절 · 비대기, Redis 실패 때의 경고 횟수. 버전 저장소와
 * 슬롯 캐시를 목으로 둔다(Redis · DB 없음).
 */
class CriteriaVersionWatcherTest {

    private static final Duration LONG_INTERVAL = Duration.ofHours(1);

    private CriteriaVersionStore versionStore;
    private CriteriaSlotCache<?> riskCache;
    private CriteriaSlotCache<?> loanCache;
    private Logger watcherLogger;
    private ListAppender<ILoggingEvent> logs;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        versionStore = mock(CriteriaVersionStore.class);
        riskCache = mock(CriteriaSlotCache.class);
        loanCache = mock(CriteriaSlotCache.class);
        watcherLogger = (Logger) LoggerFactory.getLogger(CriteriaVersionWatcher.class);
        originalLevel = watcherLogger.getLevel();
        watcherLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        watcherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        watcherLogger.detachAppender(logs);
        watcherLogger.setLevel(originalLevel);
    }

    private CriteriaVersionWatcher watcher(Duration interval) {
        return new CriteriaVersionWatcher(versionStore, List.of(riskCache, loanCache), interval);
    }

    private long warnCount() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    }

    @Test
    @DisplayName("버전이 바뀌었으면 한 번의 확인이 두 캐시를 모두 비우고 다시 읽는다")
    void changedVersionReloadsBothCaches() {
        when(versionStore.read()).thenReturn("1");

        watcher(LONG_INTERVAL).reloadIfVersionChanged();

        verify(riskCache).invalidate();
        verify(riskCache).reload();
        verify(loanCache).invalidate();
        verify(loanCache).reload();
    }

    @Test
    @DisplayName("버전이 지난 확인과 같으면 어느 캐시도 다시 읽지 않는다")
    void sameVersionDoesNotReload() {
        when(versionStore.read()).thenReturn("1");
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);
        watcher.reloadIfVersionChanged();

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        verify(riskCache, times(1)).reload();
        verify(loanCache, times(1)).reload();
    }

    @Test
    @DisplayName("버전이 다시 바뀌면 다시 읽는다")
    void versionChangeAfterwardsReloadsAgain() {
        when(versionStore.read()).thenReturn("1", "1", "2");
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        verify(riskCache, times(2)).reload();
        verify(loanCache, times(2)).reload();
    }

    @Test
    @DisplayName("기동 뒤 첫 확인은 키가 있으면 늘 다시 읽는다 — 기동과 첫 확인 사이의 변경을 놓치지 않는다")
    void firstCheckAfterStartReloadsWhenKeyExists() {
        when(versionStore.read()).thenReturn("7");

        assertThat(watcher(LONG_INTERVAL).reloadIfVersionChangedThrottled()).isTrue();

        verify(riskCache).reload();
    }

    @Test
    @DisplayName("키가 아직 없으면(null) 다시 읽지 않는다 — 처음부터 null 과 null 은 같다")
    void missingKeyDoesNotReload() {
        when(versionStore.read()).thenReturn(null);

        assertThat(watcher(LONG_INTERVAL).reloadIfVersionChangedThrottled()).isFalse();

        verify(riskCache, never()).reload();
        verify(loanCache, never()).reload();
    }

    @Test
    @DisplayName("조절된 확인은 확인 간격 안에 한 번만 버전 키를 읽는다")
    void throttledCheckReadsOncePerInterval() {
        when(versionStore.read()).thenReturn("1", "2", "3");
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);

        boolean first = watcher.reloadIfVersionChangedThrottled();
        boolean second = watcher.reloadIfVersionChangedThrottled();
        boolean third = watcher.reloadIfVersionChangedThrottled();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(third).isFalse();
        verify(versionStore, times(1)).read();
        verify(riskCache, times(1)).reload();
    }

    @Test
    @DisplayName("확인 간격이 지나면 조절된 확인이 다시 버전 키를 읽는다")
    void throttledCheckReadsAgainAfterInterval() {
        when(versionStore.read()).thenReturn("1", "2");
        CriteriaVersionWatcher watcher = watcher(Duration.ZERO);

        boolean first = watcher.reloadIfVersionChangedThrottled();
        boolean second = watcher.reloadIfVersionChangedThrottled();

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        verify(versionStore, times(2)).read();
    }

    @Test
    @DisplayName("조절된 확인은 반복 작업의 확인에 버전 키 읽기를 건너뛰지 않는다 — 요청 경로 조절은 반복 작업을 막지 않는다")
    void throttledCheckDoesNotThrottleScheduledCheck() {
        when(versionStore.read()).thenReturn("1", "2");
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);
        watcher.reloadIfVersionChangedThrottled();

        watcher.reloadIfVersionChanged();

        verify(versionStore, times(2)).read();
        verify(riskCache, times(2)).reload();
    }

    @Test
    @DisplayName("다른 확인이 진행 중이면 조절된 확인은 기다리지 않고 false 로 건너뛴다")
    void throttledCheckSkipsWhileAnotherCheckIsRunning() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        when(versionStore.read()).thenAnswer(invocation -> {
            readStarted.countDown();
            releaseRead.await(10, TimeUnit.SECONDS);
            return "1";
        });
        CriteriaVersionWatcher watcher = watcher(Duration.ZERO);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try {
                watcher.reloadIfVersionChanged();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        running.start();
        try {
            assertThat(readStarted.await(10, TimeUnit.SECONDS)).isTrue();

            boolean result = watcher.reloadIfVersionChangedThrottled();

            assertThat(result).isFalse();
            // 진행 중인 확인의 읽기 하나뿐 — 건너뛴 확인은 버전 키를 읽지 않았다.
            verify(versionStore, times(1)).read();
        } finally {
            releaseRead.countDown();
            running.join(10_000);
        }
        assertThat(failure.get()).isNull();
        verify(riskCache, times(1)).reload();
    }

    @Test
    @DisplayName("버전 키를 읽지 못하면(Redis 실패) false 이고 어느 캐시도 다시 읽지 않는다")
    void redisFailureReturnsFalseWithoutReload() {
        when(versionStore.read()).thenThrow(new IllegalStateException("redis down"));

        assertThat(watcher(LONG_INTERVAL).reloadIfVersionChangedThrottled()).isFalse();

        verify(riskCache, never()).invalidate();
        verify(riskCache, never()).reload();
        verify(loanCache, never()).reload();
    }

    @Test
    @DisplayName("반복 작업의 확인도 Redis 실패를 던지지 않는다")
    void scheduledCheckSwallowsRedisFailure() {
        when(versionStore.read()).thenThrow(new IllegalStateException("redis down"));

        watcher(LONG_INTERVAL).reloadIfVersionChanged();

        verify(riskCache, never()).reload();
    }

    @Test
    @DisplayName("Redis 실패가 이어지는 동안 경고는 한 번만 남긴다")
    void warnsOncePerFailureStreak() {
        when(versionStore.read()).thenThrow(new IllegalStateException("redis down"));
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        assertThat(warnCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("회복한 뒤 다시 실패하면 새 실패 구간이라 경고를 한 번 더 남기고, 회복은 info 로 알린다")
    void warnsAgainAfterRecovery() {
        when(versionStore.read())
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn("1")
                .thenThrow(new IllegalStateException("redis down again"));
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        assertThat(warnCount()).isEqualTo(2);
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.INFO).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패 뒤 회복하면 그때 처음 본 버전으로 다시 읽는다")
    void recoveryAfterFailureReloadsOnChangedVersion() {
        when(versionStore.read()).thenThrow(new IllegalStateException("redis down")).thenReturn("5");
        CriteriaVersionWatcher watcher = watcher(LONG_INTERVAL);

        watcher.reloadIfVersionChanged();
        verify(riskCache, never()).reload();
        watcher.reloadIfVersionChanged();

        verify(riskCache, times(1)).reload();
        verify(loanCache, times(1)).reload();
    }

    @Test
    @DisplayName("안전망 reloadAll 은 버전 키를 읽지 않고 모든 캐시를 다시 읽는다")
    void reloadAllReloadsEveryCacheWithoutReadingVersion() {
        watcher(LONG_INTERVAL).reloadAll();

        verify(riskCache).reload();
        verify(loanCache).reload();
        verify(versionStore, never()).read();
    }
}
