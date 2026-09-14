package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link RegistryRefreshBatchExecutor} 의 락 안 분기 — 재분석 조건(변동 · 분석 없음 · 분석이 등기보다 뒤처짐) · 실패를 결과로 담기.
 * 락 획득 · 해제는 관점 통합 테스트가 보고, 여기서는 락이 매물 키 · 대기 0 으로 붙어 있는지만 확인한다.
 */
class RegistryRefreshBatchExecutorTest {

    private static final long PROPERTY_ID = 2048L;
    private static final LocalDateTime ANALYZED_AT = LocalDateTime.of(2026, 9, 13, 3, 0, 5);

    private RegistryCommandService registryCommandService;
    private RiskAnalysisCommandService riskAnalysisCommandService;
    private BuildingRegistryRepository buildingRegistryRepository;
    private RiskAnalysisRepository riskAnalysisRepository;
    private RegistryRefreshBatchExecutor executor;

    @BeforeEach
    void setUp() {
        registryCommandService = mock(RegistryCommandService.class);
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        buildingRegistryRepository = mock(BuildingRegistryRepository.class);
        riskAnalysisRepository = mock(RiskAnalysisRepository.class);
        executor = new RegistryRefreshBatchExecutor(registryCommandService, riskAnalysisCommandService,
                buildingRegistryRepository, riskAnalysisRepository);
    }

    @Test
    @DisplayName("등기가 그대로이고 분석이 등기 수집 뒤에 있으면 재분석하지 않는다")
    void unchangedAndAnalysisUpToDateDoesNotAnalyze() {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.UNCHANGED);
        storedRegistryCollectedAt(ANALYZED_AT.minusMinutes(1));
        latestAnalysisAt(ANALYZED_AT);

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService, never()).analyze(any());
        assertThat(attempt.modified()).isFalse();
        assertThat(attempt.reanalyzed()).isFalse();
        assertThat(attempt.isFailed()).isFalse();
    }

    @Test
    @DisplayName("등기 수집 시각과 분석 시각이 같으면 뒤처진 것으로 보지 않는다")
    void sameInstantIsNotBehind() {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.UNCHANGED);
        storedRegistryCollectedAt(ANALYZED_AT);
        latestAnalysisAt(ANALYZED_AT);

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService, never()).analyze(any());
        assertThat(attempt.reanalyzed()).isFalse();
    }

    @Test
    @DisplayName("등기가 그대로여도 저장 등기가 최신 분석 뒤에 수집됐으면 재분석한다 — 전날 변동 뒤 분석 실패의 복구")
    void unchangedButAnalysisBehindRegistryAnalyzes() {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.UNCHANGED);
        storedRegistryCollectedAt(ANALYZED_AT.plusSeconds(1));
        latestAnalysisAt(ANALYZED_AT);

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        assertThat(attempt.modified()).isFalse();
        assertThat(attempt.reanalyzed()).isTrue();
        assertThat(attempt.isFailed()).isFalse();
    }

    @Test
    @DisplayName("등기가 그대로여도 최신 분석이 없으면 재분석한다")
    void unchangedWithoutAnalysisAnalyzes() {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.UNCHANGED);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        assertThat(attempt.reanalyzed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = RegistryRefreshOutcome.class, names = {"CHANGED", "COLLECTED"})
    @DisplayName("등기가 바뀌었거나 처음 수집했으면 시각을 보지 않고 재분석한다")
    void modifiedAnalyzes(RegistryRefreshOutcome outcome) {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(outcome);

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        verifyNoInteractions(buildingRegistryRepository, riskAnalysisRepository);
        assertThat(attempt.outcome()).isEqualTo(outcome);
        assertThat(attempt.reanalyzed()).isTrue();
        assertThat(attempt.isFailed()).isFalse();
    }

    @Test
    @DisplayName("재조회가 실패하면 예외를 던지지 않고 실패 결과로 담는다 — 밖으로 나오는 503 은 락 경합뿐이어야 한다")
    void refreshFailureIsReturnedNotThrown() {
        BusinessException failure = new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        when(registryCommandService.refresh(PROPERTY_ID)).thenThrow(failure);

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        verify(riskAnalysisCommandService, never()).analyze(any());
        assertThat(attempt.failure()).isSameAs(failure);
        assertThat(attempt.outcome()).isNull();
        assertThat(attempt.modified()).isFalse();
        assertThat(attempt.skipped()).isFalse();
    }

    @Test
    @DisplayName("변동 뒤 재분석이 실패하면 변동은 남기고 재분석은 끝나지 않은 실패로 담는다")
    void analyzeFailureKeepsOutcome() {
        when(registryCommandService.refresh(PROPERTY_ID)).thenReturn(RegistryRefreshOutcome.CHANGED);
        when(riskAnalysisCommandService.analyze(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        RegistryRefreshAttempt attempt = executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID);

        assertThat(attempt.modified()).isTrue();
        assertThat(attempt.reanalyzed()).isFalse();
        assertThat(attempt.isFailed()).isTrue();
    }

    @Test
    @DisplayName("매물 단위 메서드에 사용자 재분석과 같은 매물 키의 분산 락이 대기 0 으로 붙어 있다")
    void lockAnnotationUsesPropertyKeyWithoutWaiting() throws NoSuchMethodException {
        DistributedLock lock = RegistryRefreshBatchExecutor.class
                .getMethod("refreshAndAnalyzeIfNeeded", Long.class)
                .getAnnotation(DistributedLock.class);
        DistributedLock userLock = RiskReanalysisExecutor.class.getMethod("refreshAndAnalyze", Long.class)
                .getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.key()).isEqualTo(userLock.key());
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo(userLock.leaseTime());
    }

    private void storedRegistryCollectedAt(LocalDateTime collectedAt) {
        BuildingRegistry registry = mock(BuildingRegistry.class);
        when(registry.getUpdatedAt()).thenReturn(collectedAt);
        when(buildingRegistryRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(registry));
    }

    private void latestAnalysisAt(LocalDateTime analyzedAt) {
        RiskAnalysis analysis = mock(RiskAnalysis.class);
        when(analysis.getCreatedAt()).thenReturn(analyzedAt);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(analysis));
    }
}
