package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.store.ReanalyzeIntervalStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link RiskReanalysisExecutor} 의 락 안 분기 — 간격 재확인 · 재조회 순서 · 실패 시 간격 미설정. 락 획득 · 대기 · 해제는 관점이
 * 갖고 관점 통합 테스트가 본다. 여기서는 락이 매물 키로 붙어 있는지만 확인한다.
 */
class RiskReanalysisExecutorTest {

    private static final long PROPERTY_ID = 1024L;

    private RegistryCommandService registryCommandService;
    private RiskAnalysisCommandService riskAnalysisCommandService;
    private ReanalyzeIntervalStore intervalStore;
    private RiskReanalysisExecutor executor;

    @BeforeEach
    void setUp() {
        registryCommandService = mock(RegistryCommandService.class);
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        intervalStore = mock(ReanalyzeIntervalStore.class);
        executor = new RiskReanalysisExecutor(registryCommandService, riskAnalysisCommandService, intervalStore);

        when(intervalStore.remaining(PROPERTY_ID)).thenReturn(Optional.empty());
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.UNCHANGED);
    }

    @Test
    @DisplayName("간격이 없으면 재조회 → 분석 → 간격 설정 순으로 진행하고 분석 결과를 돌려준다")
    void refreshesAnalyzesThenMarks() {
        RiskResponse analyzed = analyzed();
        when(riskAnalysisCommandService.analyze(PROPERTY_ID)).thenReturn(analyzed);

        RiskResponse result = executor.refreshAndAnalyze(PROPERTY_ID);

        InOrder order = inOrder(registryCommandService, riskAnalysisCommandService, intervalStore);
        order.verify(registryCommandService).refresh(PROPERTY_ID);
        order.verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        order.verify(intervalStore).markAnalyzed(PROPERTY_ID);
        assertThat(result).isSameAs(analyzed);
    }

    @Test
    @DisplayName("락을 넘겨받았을 때 간격이 이미 걸려 있으면 재조회 없이 분석만 한다")
    void intervalAlreadySetSkipsRefresh() {
        when(intervalStore.remaining(PROPERTY_ID)).thenReturn(Optional.of(Duration.ofMinutes(10)));
        RiskResponse analyzed = analyzed();
        when(riskAnalysisCommandService.analyze(PROPERTY_ID)).thenReturn(analyzed);

        RiskResponse result = executor.refreshAndAnalyze(PROPERTY_ID);

        assertThat(result).isSameAs(analyzed);
        verify(registryCommandService, never()).refresh(any());
        verify(intervalStore, never()).markAnalyzed(any());
    }

    @Test
    @DisplayName("재조회가 실패하면 예외를 올리고 분석 · 간격 설정을 하지 않는다")
    void refreshFailureDoesNotMark() {
        when(registryCommandService.refresh(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> executor.refreshAndAnalyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        verify(riskAnalysisCommandService, never()).analyze(any());
        verify(intervalStore, never()).markAnalyzed(any());
    }

    @Test
    @DisplayName("분석이 실패하면 간격을 걸지 않는다")
    void analyzeFailureDoesNotMark() {
        when(riskAnalysisCommandService.analyze(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> executor.refreshAndAnalyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class);
        verify(intervalStore, never()).markAnalyzed(any());
    }

    @Test
    @DisplayName("재분석 메서드에 매물 키 risk:analysis:lock:{propertyId} 의 분산 락이 설정 키로 붙어 있다")
    void lockAnnotationUsesPropertyKey() throws NoSuchMethodException {
        DistributedLock lock = RiskReanalysisExecutor.class.getMethod("refreshAndAnalyze", Long.class)
                .getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.key()).isEqualTo("'risk:analysis:lock:' + #propertyId");
        assertThat(lock.waitTimeout()).isEqualTo("${risk.reanalyze.lock-wait-timeout}");
        assertThat(lock.pollInterval()).isEqualTo("${risk.reanalyze.lock-poll-interval}");
        assertThat(lock.leaseTime()).isEqualTo("${risk.reanalyze.lock-lease-time}");
    }

    private static RiskResponse analyzed() {
        return new RiskResponse(RiskGrade.SAFE, null, null, null, null, null, 0L, false, false, null, null, null,
                null, null, null);
    }
}
