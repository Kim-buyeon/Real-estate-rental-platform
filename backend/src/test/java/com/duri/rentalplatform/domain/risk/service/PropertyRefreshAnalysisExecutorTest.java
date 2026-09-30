package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.service.PropertyLoadWriter;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * {@link PropertyRefreshAnalysisExecutor} — 락 안에서 판정만 하고 실패를 결과로 담는다. 락 획득 · 해제는 관점 통합 테스트가 보고,
 * 여기서는 락이 사용자 재분석과 같은 매물 키로 · 기다리지 않고 붙어 있는지 확인한다.
 */
class PropertyRefreshAnalysisExecutorTest {

    private static final long PROPERTY_ID = 2048L;

    private RiskAnalysisCommandService riskAnalysisCommandService;
    private PropertyLoadWriter propertyLoadWriter;
    private PropertyRefreshAnalysisExecutor executor;

    @BeforeEach
    void setUp() {
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        propertyLoadWriter = mock(PropertyLoadWriter.class);
        executor = new PropertyRefreshAnalysisExecutor(riskAnalysisCommandService, propertyLoadWriter);
    }

    @Test
    @DisplayName("시세가 바뀐 매물은 판정을 다시 돌린다 — 이전 등급 보존과 등급 변동 이벤트는 판정 서비스가 갖는다")
    void reanalyzesPriceChangedProperty() {
        PropertyRefreshTarget target = PropertyRefreshTarget.priceChanged(PROPERTY_ID);

        PropertyRefreshAttempt attempt = executor.analyze(target);

        verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        assertThat(attempt.analyzed()).isTrue();
        assertThat(attempt.isFailed()).isFalse();
        assertThat(attempt.target()).isEqualTo(target);
    }

    @Test
    @DisplayName("시세 변경 재분석을 마치면 판정 뒤에 재분석 대기 표시를 내린다 — 결론이 같아 판정 행이 늘지 않아도 매 회차 다시 나오지 않게")
    void clearsReanalysisPendingAfterReanalysis() {
        executor.analyze(PropertyRefreshTarget.priceChanged(PROPERTY_ID));

        InOrder order = inOrder(riskAnalysisCommandService, propertyLoadWriter);
        order.verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        order.verify(propertyLoadWriter).completeReanalysis(PROPERTY_ID);
    }

    @Test
    @DisplayName("재분석이 실패하면 재분석 대기 표시를 내리지 않는다 — 다음 회차가 다시 잡는다")
    void keepsReanalysisPendingWhenAnalysisFails() {
        when(riskAnalysisCommandService.analyze(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        PropertyRefreshAttempt attempt = executor.analyze(PropertyRefreshTarget.priceChanged(PROPERTY_ID));

        assertThat(attempt.isFailed()).isTrue();
        verify(propertyLoadWriter, never()).completeReanalysis(PROPERTY_ID);
    }

    @Test
    @DisplayName("재분석 대기 표시를 내리다 실패하면 실패로 담는다 — 표시가 남아 다음 회차에 한 번 더 판정한다")
    void clearFailureIsCapturedAsResult() {
        IllegalStateException failure = new IllegalStateException("db down");
        doThrow(failure).when(propertyLoadWriter).completeReanalysis(PROPERTY_ID);

        PropertyRefreshAttempt attempt = executor.analyze(PropertyRefreshTarget.priceChanged(PROPERTY_ID));

        assertThat(attempt.failure()).isSameAs(failure);
    }

    @Test
    @DisplayName("최신 판정이 없는 매물도 같은 판정을 돌린다")
    void analyzesUnanalyzedProperty() {
        PropertyRefreshAttempt attempt = executor.analyze(PropertyRefreshTarget.unanalyzed(PROPERTY_ID));

        verify(riskAnalysisCommandService).analyze(PROPERTY_ID);
        assertThat(attempt.analyzed()).isTrue();
        // 미판정 갈래는 재분석 대기 매물을 빼고 조회하므로 내릴 표시가 없다.
        verify(propertyLoadWriter, never()).completeReanalysis(PROPERTY_ID);
    }

    @Test
    @DisplayName("판정이 실패하면(등기 · 대장 수집 503 등) 던지지 않고 결과에 담는다 — 락 밖의 503 과 섞이지 않게")
    void failureIsCapturedAsResult() {
        BusinessException failure = new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        when(riskAnalysisCommandService.analyze(PROPERTY_ID)).thenThrow(failure);

        PropertyRefreshAttempt attempt = executor.analyze(PropertyRefreshTarget.unanalyzed(PROPERTY_ID));

        assertThat(attempt.analyzed()).isFalse();
        assertThat(attempt.skipped()).isFalse();
        assertThat(attempt.failure()).isSameAs(failure);
    }

    @Test
    @DisplayName("락은 사용자 재분석과 같은 매물 키 risk:analysis:lock:{propertyId} 로 풀리고, 기다리지 않으며 만료는 재분석 설정을 쓴다")
    void lockAnnotationUsesPropertyKeyWithoutWaiting() throws NoSuchMethodException {
        Method analyze = PropertyRefreshAnalysisExecutor.class.getMethod("analyze", PropertyRefreshTarget.class);
        DistributedLock lock = analyze.getAnnotation(DistributedLock.class);
        DistributedLock userLock = RiskReanalysisExecutor.class.getMethod("refreshAndAnalyze", Long.class)
                .getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo(userLock.leaseTime());

        // 관점과 같은 방식으로 평가한다 — 인자 이름 #target 과 그 접근자가 읽히는지까지 확인한다.
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(executor, analyze,
                new Object[] {PropertyRefreshTarget.priceChanged(PROPERTY_ID)}, new DefaultParameterNameDiscoverer());
        String key = new SpelExpressionParser().parseExpression(lock.key()).getValue(context, String.class);
        assertThat(key).isEqualTo("risk:analysis:lock:" + PROPERTY_ID);
    }
}
