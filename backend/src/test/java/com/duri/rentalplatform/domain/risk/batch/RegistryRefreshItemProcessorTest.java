package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.service.RegistryRefreshBatchExecutor;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** {@link RegistryRefreshItemProcessor} — 실행기 결과를 그대로 넘기고, 락 밖 예외는 던지지 않고 건너뜀 · 실패 결과로 바꾼다. */
class RegistryRefreshItemProcessorTest {

    private static final long PROPERTY_ID = 7L;

    private RegistryRefreshBatchExecutor executor;
    private RegistryRefreshItemProcessor processor;

    @BeforeEach
    void setUp() {
        executor = mock(RegistryRefreshBatchExecutor.class);
        processor = new RegistryRefreshItemProcessor(executor);
    }

    @Test
    @DisplayName("실행기의 결과를 그대로 넘긴다 — 락 안 실패 결과도 마찬가지다")
    void passesExecutorResult() {
        RegistryRefreshAttempt completed = RegistryRefreshAttempt.completed(RegistryRefreshOutcome.CHANGED, true);
        when(executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID)).thenReturn(completed);

        assertThat(processor.process(PROPERTY_ID)).isSameAs(completed);
    }

    @Test
    @DisplayName("매물 락 경합(503)은 예외 없이 건너뜀 결과다")
    void lockContentionBecomesSkipped() {
        when(executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        RegistryRefreshAttempt attempt = processor.process(PROPERTY_ID);

        assertThat(attempt.skipped()).isTrue();
        assertThat(attempt.isFailed()).isFalse();
    }

    @Test
    @DisplayName("락 획득 중 오류(Redis 장애 등)는 예외 없이 실패 결과다 — 스텝이 멈추지 않는다")
    void lockInfrastructureFailureBecomesFailed() {
        QueryTimeoutException failure = new QueryTimeoutException("redis");
        when(executor.refreshAndAnalyzeIfNeeded(PROPERTY_ID)).thenThrow(failure);

        RegistryRefreshAttempt attempt = processor.process(PROPERTY_ID);

        assertThat(attempt.failure()).isSameAs(failure);
        assertThat(attempt.skipped()).isFalse();
    }
}
