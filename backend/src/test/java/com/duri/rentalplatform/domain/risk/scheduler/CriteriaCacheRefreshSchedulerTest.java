package com.duri.rentalplatform.domain.risk.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.duri.rentalplatform.common.cache.CriteriaVersionWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link CriteriaCacheRefreshScheduler} — 두 반복 작업이 감시자의 서로 다른 동작을 부른다. 동작 자체는 감시자 테스트가 본다. */
class CriteriaCacheRefreshSchedulerTest {

    private CriteriaVersionWatcher cache;
    private CriteriaCacheRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        cache = mock(CriteriaVersionWatcher.class);
        scheduler = new CriteriaCacheRefreshScheduler(cache);
    }

    @Test
    @DisplayName("버전 확인 작업은 버전이 바뀌었을 때만 읽는 동작을 부른다 — 무조건 다시 읽기는 부르지 않는다")
    void versionCheckCallsReloadIfVersionChanged() {
        scheduler.reloadIfVersionChanged();

        verify(cache).reloadIfVersionChanged();
        verify(cache, never()).reloadAll();
    }

    @Test
    @DisplayName("안전망 작업은 버전과 무관하게 다시 읽는 동작을 부른다")
    void safetyNetCallsReload() {
        scheduler.reloadAll();

        verify(cache).reloadAll();
        verify(cache, never()).reloadIfVersionChanged();
    }
}
