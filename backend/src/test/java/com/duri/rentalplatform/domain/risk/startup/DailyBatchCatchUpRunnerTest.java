package com.duri.rentalplatform.domain.risk.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.scheduler.MockLedgerReplaceScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.PropertyRefreshScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.RegistryRefreshScheduler;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.QueryTimeoutException;

/**
 * {@link DailyBatchCatchUpRunner} — 오늘(서울) 성공 기록이 없는 배치만 정해진 순서로 스케줄러 진입점을 부르고, 한 배치가 실패해도
 * 다음 배치로 넘어가며, 스케줄러 빈이 없는 배치는 건너뛴다. 날짜 락 · 락 경합 처리는 각 스케줄러 테스트가 본다.
 */
class DailyBatchCatchUpRunnerTest {

    /** UTC 9/29 17:00 = 서울 9/30 02:00. 오늘이 서버 시간대가 아니라 서울로 정해지는지 가른다. */
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-09-29T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEOUL_TODAY = LocalDate.of(2026, 9, 30);

    private PropertyRefreshScheduler propertyRefresh;
    private RegistryRefreshScheduler registryRefresh;
    private MockLedgerReplaceScheduler mockLedgerReplace;
    private BatchSuccessStore successStore;

    @BeforeEach
    void setUp() {
        propertyRefresh = mock(PropertyRefreshScheduler.class);
        registryRefresh = mock(RegistryRefreshScheduler.class);
        mockLedgerReplace = mock(MockLedgerReplaceScheduler.class);
        successStore = mock(BatchSuccessStore.class);
    }

    @Test
    @DisplayName("오늘 기록이 없으면 매물 갱신 → 등기 재조회 → Mock 대장 교체 순서로 부른다")
    void runsMissedBatchesInOrder() {
        catchUp(propertyRefresh, registryRefresh, mockLedgerReplace).catchUp();

        InOrder order = inOrder(propertyRefresh, registryRefresh, mockLedgerReplace);
        order.verify(propertyRefresh).refreshProperties();
        order.verify(registryRefresh).refreshWishlistedRegistries();
        order.verify(mockLedgerReplace).replaceMockLedgers();
    }

    @Test
    @DisplayName("서울 기준 오늘 날짜로 성공 기록을 보고, 오늘 이미 성공한 배치는 부르지 않는다")
    void skipsBatchesAlreadySucceededToday() {
        when(successStore.hasSucceeded(DailyBatch.PROPERTY_REFRESH, SEOUL_TODAY)).thenReturn(true);
        when(successStore.hasSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, SEOUL_TODAY)).thenReturn(true);

        catchUp(propertyRefresh, registryRefresh, mockLedgerReplace).catchUp();

        verify(propertyRefresh, never()).refreshProperties();
        verify(registryRefresh).refreshWishlistedRegistries();
        verify(mockLedgerReplace, never()).replaceMockLedgers();
    }

    @Test
    @DisplayName("앞 배치가 예외로 끝나도 다음 배치는 돌고, 예외는 밖으로 새지 않는다")
    void failureDoesNotStopNextBatch() {
        doThrow(new IllegalStateException("boom")).when(propertyRefresh).refreshProperties();
        doThrow(new IllegalStateException("boom")).when(registryRefresh).refreshWishlistedRegistries();

        assertThatCode(() -> catchUp(propertyRefresh, registryRefresh, mockLedgerReplace).catchUp())
                .doesNotThrowAnyException();

        verify(registryRefresh).refreshWishlistedRegistries();
        verify(mockLedgerReplace).replaceMockLedgers();
    }

    @Test
    @DisplayName("성공 기록을 읽지 못한 배치는 건너뛰고 다음 배치로 넘어간다")
    void storeFailureSkipsOnlyThatBatch() {
        when(successStore.hasSucceeded(DailyBatch.PROPERTY_REFRESH, SEOUL_TODAY))
                .thenThrow(new QueryTimeoutException("redis"));

        catchUp(propertyRefresh, registryRefresh, mockLedgerReplace).catchUp();

        verify(propertyRefresh, never()).refreshProperties();
        verify(registryRefresh).refreshWishlistedRegistries();
        verify(mockLedgerReplace).replaceMockLedgers();
    }

    @Test
    @DisplayName("스케줄러 빈이 없는 배치(꺼짐 · 대장 연동 real 아님)는 기록도 보지 않고 건너뛴다")
    void skipsBatchesWithoutScheduler() {
        catchUp(propertyRefresh, null, null).catchUp();

        verify(propertyRefresh).refreshProperties();
        verify(successStore, never()).hasSucceeded(DailyBatch.REGISTRY_REFRESH, SEOUL_TODAY);
        verify(successStore, never()).hasSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, SEOUL_TODAY);
    }

    @Test
    @DisplayName("기동 이벤트는 따라잡기를 실행기에 넘기기만 한다 — 준비 상태를 늦추지 않는다")
    void startupHandsOffToExecutor() {
        List<Runnable> submitted = new ArrayList<>();
        DailyBatchCatchUpRunner scheduler = new DailyBatchCatchUpRunner(
                propertyRefresh, registryRefresh, mockLedgerReplace, successStore, UTC_CLOCK, submitted::add);

        scheduler.catchUpOnStartup();

        assertThat(submitted).hasSize(1);
        verify(propertyRefresh, never()).refreshProperties();
        verify(successStore, never()).hasSucceeded(any(), any());

        submitted.getFirst().run();

        verify(propertyRefresh).refreshProperties();
    }

    @Test
    @DisplayName("ApplicationReadyEvent 에 걸리고, batch.startup-catchup.enabled 일 때만 뜬다")
    void annotations() throws NoSuchMethodException {
        EventListener listener = DailyBatchCatchUpRunner.class.getMethod("catchUpOnStartup")
                .getAnnotation(EventListener.class);
        ConditionalOnBooleanProperty enabled =
                DailyBatchCatchUpRunner.class.getAnnotation(ConditionalOnBooleanProperty.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(enabled.value()).containsExactly("batch.startup-catchup.enabled");
    }

    private DailyBatchCatchUpRunner catchUp(
            PropertyRefreshScheduler property, RegistryRefreshScheduler registry, MockLedgerReplaceScheduler mockLedger) {
        return new DailyBatchCatchUpRunner(property, registry, mockLedger, successStore, UTC_CLOCK, Runnable::run);
    }
}
