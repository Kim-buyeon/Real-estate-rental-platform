package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.dto.response.RiskReanalyzeResponse;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.store.ReanalyzeIntervalStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RiskReanalysisCommandService} 의 분기 — 404 · 429 · 실행기 위임 · 실행기 실패 전파 · 등급 변경 여부. 락 안의 재조회 ·
 * 재확인은 {@link RiskReanalysisExecutorTest}, 락 자체는 관점 통합 테스트가 본다.
 */
class RiskReanalysisCommandServiceTest {

    private static final long PROPERTY_ID = 1024L;
    private static final OffsetDateTime ANALYZED_AT = OffsetDateTime.of(2026, 7, 29, 10, 12, 0, 0,
            ZoneOffset.ofHours(9));

    private PropertyRepository propertyRepository;
    private RiskAnalysisRepository riskAnalysisRepository;
    private ReanalyzeIntervalStore intervalStore;
    private RiskReanalysisExecutor executor;
    private RiskReanalysisCommandService service;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        riskAnalysisRepository = mock(RiskAnalysisRepository.class);
        intervalStore = mock(ReanalyzeIntervalStore.class);
        executor = mock(RiskReanalysisExecutor.class);
        service = new RiskReanalysisCommandService(propertyRepository, riskAnalysisRepository, intervalStore,
                executor);

        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);
        when(intervalStore.remaining(PROPERTY_ID)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("매물이 없으면 404 PROPERTY_NOT_FOUND — 간격 · 실행기를 보지 않는다")
    void propertyNotFound() {
        when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.reanalyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
        verifyNoInteractions(intervalStore, executor);
    }

    @Test
    @DisplayName("간격 안이면 429 RISK_REANALYZE_TOO_SOON 과 지금 + 남은 시간(서울 오프셋, 초 올림)을 낸다")
    void tooSoon() {
        when(intervalStore.remaining(PROPERTY_ID)).thenReturn(Optional.of(Duration.ofMinutes(7)));
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.ofHours(9));

        BusinessException thrown = catchThrowableOfType(BusinessException.class,
                () -> service.reanalyze(PROPERTY_ID));

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.ofHours(9));
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.RISK_REANALYZE_TOO_SOON);
        assertThat(thrown.getRetryAfter().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(thrown.getRetryAfter().getNano()).isZero();
        assertThat(thrown.getRetryAfter())
                .isAfterOrEqualTo(before.plusMinutes(7).withNano(0))
                .isBeforeOrEqualTo(after.plusMinutes(7).plusSeconds(1));
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("간격 밖이면 실행기의 분석 결과로 응답한다")
    void delegatesToExecutor() {
        when(executor.refreshAndAnalyze(PROPERTY_ID)).thenReturn(analyzed(RiskGrade.SAFE));

        RiskReanalyzeResponse response = service.reanalyze(PROPERTY_ID);

        assertThat(response.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(response.analyzedAt()).isEqualTo(ANALYZED_AT);
    }

    @Test
    @DisplayName("실행기가 503 을 던지면(수집 실패 · 락 대기 초과) 그대로 올린다")
    void executorFailurePropagates() {
        when(executor.refreshAndAnalyze(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> service.reanalyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("직전 등급과 다르면 gradeChanged = true, previousGrade 는 요청 시점의 최신 등급")
    void gradeChanged() {
        givenLatestGrade(RiskGrade.CAUTION);
        when(executor.refreshAndAnalyze(PROPERTY_ID)).thenReturn(analyzed(RiskGrade.DANGER));

        RiskReanalyzeResponse response = service.reanalyze(PROPERTY_ID);

        assertThat(response.previousGrade()).isEqualTo(RiskGrade.CAUTION);
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.DANGER);
        assertThat(response.gradeChanged()).isTrue();
    }

    @Test
    @DisplayName("직전 등급과 같으면 gradeChanged = false")
    void gradeUnchanged() {
        givenLatestGrade(RiskGrade.SAFE);
        when(executor.refreshAndAnalyze(PROPERTY_ID)).thenReturn(analyzed(RiskGrade.SAFE));

        RiskReanalyzeResponse response = service.reanalyze(PROPERTY_ID);

        assertThat(response.previousGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(response.gradeChanged()).isFalse();
    }

    @Test
    @DisplayName("분석된 적이 없으면 previousGrade = null, gradeChanged = false")
    void firstAnalysis() {
        when(executor.refreshAndAnalyze(PROPERTY_ID)).thenReturn(analyzed(RiskGrade.DANGER));

        RiskReanalyzeResponse response = service.reanalyze(PROPERTY_ID);

        assertThat(response.previousGrade()).isNull();
        assertThat(response.gradeChanged()).isFalse();
    }

    private void givenLatestGrade(RiskGrade grade) {
        RiskAnalysis latest = mock(RiskAnalysis.class);
        when(latest.getRiskGrade()).thenReturn(grade);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));
    }

    /** 재분석 응답이 쓰는 것은 등급과 분석 시각뿐이다. 나머지 근거는 비워 둔다. */
    private static RiskResponse analyzed(RiskGrade grade) {
        return new RiskResponse(grade, null, null, null, null, null, 0L, false, false, null, null, null, null,
                null, ANALYZED_AT);
    }
}
