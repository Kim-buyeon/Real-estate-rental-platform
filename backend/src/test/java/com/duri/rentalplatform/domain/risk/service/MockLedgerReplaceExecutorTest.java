package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.property.vo.LedgerReplacement;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link MockLedgerReplaceExecutor} 의 락 안 분기 — 교체 · 삭제 · 상한 · 재분석, 실패를 결과로 담기. 삭제의 실제 SQL(참조 끊기 →
 * 행 삭제)은 통합 테스트가 보고, 여기서는 순서와 호출만 본다.
 */
class MockLedgerReplaceExecutorTest {

    private static final long PROPERTY_ID = 4096L;
    private static final long LEDGER_ID = 77L;

    private LedgerCommandService ledgerCommandService;
    private RiskAnalysisCommandService riskAnalysisCommandService;
    private BuildingLedgerRepository buildingLedgerRepository;
    private RiskAnalysisRepository riskAnalysisRepository;
    private PropertyRepository propertyRepository;
    private MockLedgerReplaceExecutor executor;

    @BeforeEach
    void setUp() {
        ledgerCommandService = mock(LedgerCommandService.class);
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        buildingLedgerRepository = mock(BuildingLedgerRepository.class);
        riskAnalysisRepository = mock(RiskAnalysisRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        executor = new MockLedgerReplaceExecutor(ledgerCommandService, riskAnalysisCommandService,
                buildingLedgerRepository, riskAnalysisRepository, propertyRepository,
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("건축HUB 대장을 뗐으면 Mock 행을 바꾸고 대장 수집 없이 재분석한다")
    void fetchedReplacesAndReanalyzes() {
        BuildingLedgerDocument document = buildingHubDocument();
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID)).thenReturn(LedgerReplacement.fetched(document));

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        InOrder order = inOrder(ledgerCommandService, riskAnalysisCommandService);
        order.verify(ledgerCommandService).replaceMock(PROPERTY_ID, document);
        order.verify(riskAnalysisCommandService).analyzeWithCollectedLedger(PROPERTY_ID);
        verify(riskAnalysisCommandService, never()).analyze(any());
        verify(buildingLedgerRepository, never()).delete(any());
        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.FETCHED, true));
    }

    @Test
    @DisplayName("뗄 대장이 없으면 분석 행의 참조를 먼저 끊고 Mock 행을 지운 뒤 재분석한다")
    void notFoundDetachesDeletesAndReanalyzes() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NOT_FOUND));
        BuildingLedger ledger = storedLedger(true);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        InOrder order = inOrder(riskAnalysisRepository, buildingLedgerRepository, riskAnalysisCommandService);
        order.verify(riskAnalysisRepository).detachLedger(LEDGER_ID);
        order.verify(buildingLedgerRepository).delete(ledger);
        order.verify(riskAnalysisCommandService).analyzeWithCollectedLedger(PROPERTY_ID);
        verify(ledgerCommandService, never()).replaceMock(any(), any());
        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.NOT_FOUND, true));
    }

    @Test
    @DisplayName("삭제 직전에 행이 Mock 이 아니게 됐으면 참조도 행도 건드리지 않는다")
    void notFoundSkipsDeletionWhenNoLongerMock() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NOT_FOUND));
        storedLedger(false);

        executor.replaceAndAnalyze(PROPERTY_ID);

        verify(riskAnalysisRepository, never()).detachLedger(any());
        verify(buildingLedgerRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Mock 행을 지울 때 같은 쓰기 트랜잭션에서 재분석 대기를 세운다 — 참조 끊기 · 삭제 뒤, 재분석 앞")
    void removeMockMarksReanalysisPending() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NOT_FOUND));
        BuildingLedger ledger = storedLedger(true);

        executor.replaceAndAnalyze(PROPERTY_ID);

        InOrder order = inOrder(buildingLedgerRepository, propertyRepository, riskAnalysisCommandService);
        order.verify(buildingLedgerRepository).delete(ledger);
        order.verify(propertyRepository).markReanalysisPending(PROPERTY_ID);
        order.verify(riskAnalysisCommandService).analyzeWithCollectedLedger(PROPERTY_ID);
    }

    @Test
    @DisplayName("삭제 직전에 행이 Mock 이 아니게 됐으면 재분석 대기도 세우지 않는다")
    void removeMockSkippedDoesNotMarkReanalysisPending() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NOT_FOUND));
        storedLedger(false);

        executor.replaceAndAnalyze(PROPERTY_ID);

        verify(propertyRepository, never()).markReanalysisPending(any());
    }

    @ParameterizedTest
    @EnumSource(value = LedgerReplacementOutcome.class, names = {"NOT_MOCK", "QUOTA_EXHAUSTED", "RATE_LIMITED"})
    @DisplayName("이미 Mock 이 아니거나 일일 · 초당 한도에 닿았으면 아무것도 바꾸지 않고 재분석하지 않는다")
    void untouchedOutcomes(LedgerReplacementOutcome outcome) {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID)).thenReturn(LedgerReplacement.of(outcome));

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        verify(ledgerCommandService, never()).replaceMock(any(), any());
        verifyNoInteractions(buildingLedgerRepository, riskAnalysisRepository, riskAnalysisCommandService);
        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.completed(outcome, false));
    }

    @Test
    @DisplayName("대장 행이 없는 매물은 수집 경로로 떼어 새로 저장했으면 대장 수집 없이 재분석하고 COLLECTED")
    void noLedgerCollectsAndReanalyzes() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NO_LEDGER));
        when(ledgerCommandService.collectIfAbsent(PROPERTY_ID)).thenReturn(true);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        InOrder order = inOrder(ledgerCommandService, riskAnalysisCommandService);
        order.verify(ledgerCommandService).collectIfAbsent(PROPERTY_ID);
        order.verify(riskAnalysisCommandService).analyzeWithCollectedLedger(PROPERTY_ID);
        verify(riskAnalysisCommandService, never()).analyze(any());
        verifyNoInteractions(buildingLedgerRepository, riskAnalysisRepository);
        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.COLLECTED, true));
    }

    @Test
    @DisplayName("대장 행이 없는 매물을 떼지 못했으면(없음 · 한도) 그대로 두고 재분석하지 않는다 — NO_LEDGER")
    void noLedgerNotCollectedIsSkipped() {
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID))
                .thenReturn(LedgerReplacement.of(LedgerReplacementOutcome.NO_LEDGER));
        when(ledgerCommandService.collectIfAbsent(PROPERTY_ID)).thenReturn(false);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        verifyNoInteractions(buildingLedgerRepository, riskAnalysisRepository, riskAnalysisCommandService);
        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.NO_LEDGER, false));
    }

    @Test
    @DisplayName("대장 연동이 실패하면 결과에 담고 결과(교체 · 삭제)는 비운다 — Mock 행은 그대로다")
    void fetchFailureIsCaptured() {
        BusinessException failure = new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID)).thenThrow(failure);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.failed(null, failure));
        verifyNoInteractions(riskAnalysisCommandService);
    }

    @Test
    @DisplayName("교체 저장이 실패하면 교체로 세지 않는다 — 결과는 비운 채 실패")
    void replaceFailureIsNotCountedAsReplaced() {
        BuildingLedgerDocument document = buildingHubDocument();
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID)).thenReturn(LedgerReplacement.fetched(document));
        IllegalStateException failure = new IllegalStateException("db");
        doThrow(failure).when(ledgerCommandService).replaceMock(PROPERTY_ID, document);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.failed(null, failure));
        verifyNoInteractions(riskAnalysisCommandService);
    }

    @Test
    @DisplayName("교체 뒤 재분석이 실패하면 교체 결과와 실패를 함께 담는다")
    void reanalysisFailureKeepsOutcome() {
        BuildingLedgerDocument document = buildingHubDocument();
        when(ledgerCommandService.fetchMockReplacement(PROPERTY_ID)).thenReturn(LedgerReplacement.fetched(document));
        BusinessException failure = new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        when(riskAnalysisCommandService.analyzeWithCollectedLedger(PROPERTY_ID)).thenThrow(failure);

        MockLedgerReplaceAttempt attempt = executor.replaceAndAnalyze(PROPERTY_ID);

        assertThat(attempt).isEqualTo(MockLedgerReplaceAttempt.failed(LedgerReplacementOutcome.FETCHED, failure));
    }

    @Test
    @DisplayName("매물 분석 락이 매물 키 · 대기 0 · 분석 락 만료로 붙어 있다")
    void lockAnnotation() throws NoSuchMethodException {
        DistributedLock lock = MockLedgerReplaceExecutor.class.getMethod("replaceAndAnalyze", Long.class)
                .getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.key()).isEqualTo("'risk:analysis:lock:' + #propertyId");
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo("${risk.reanalyze.lock-lease-time}");
    }

    private BuildingLedger storedLedger(boolean mockSource) {
        BuildingLedger ledger = mock(BuildingLedger.class);
        when(ledger.getLedgerId()).thenReturn(LEDGER_ID);
        when(ledger.isMock()).thenReturn(mockSource);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));
        return ledger;
    }

    private static BuildingLedgerDocument buildingHubDocument() {
        return new BuildingLedgerDocument("서울특별시 종로구 동망산길 19 (창신동)", null, "공동주택", "철근콘크리트구조",
                null, new BigDecimal("14544.66"), null, LocalDate.of(1992, 11, 25), null, LedgerDataSource.BUILDING_HUB);
    }
}
