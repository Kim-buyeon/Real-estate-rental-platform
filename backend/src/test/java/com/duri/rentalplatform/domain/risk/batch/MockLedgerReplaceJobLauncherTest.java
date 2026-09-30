package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.dto.condition.LedgerSourceTargetCondition;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.service.MockLedgerReplaceExecutor;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceReport;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * {@link MockLedgerReplaceJobLauncher} — 스프링 컨텍스트 없이 실제 Spring Batch 로 한 회차를 돌린다(등기 재조회 배치 시험과 같은
 * 방식). 대상 조회 · 상한 · 매물 단위 실행기만 목이다.
 *
 * <p>여기서 보는 것 — 관심 매물이 먼저 처리되는가, 매물별 결과가 회차 집계로 돌아오는가(처리 · 쓰기 단계 포함), 상한에 닿으면
 * 남은 대상이 있어도 끝나는가, 스텝 실패를 삼키지 않는가, 날짜 락이 붙어 있는가.
 */
class MockLedgerReplaceJobLauncherTest {

    private static final int CHUNK_SIZE = 2;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final Clock AT_0430 = Clock.fixed(Instant.parse("2026-09-30T19:30:00Z"), ZoneOffset.UTC);

    private LedgerMapper ledgerMapper;
    private BuildingLedgerDailyQuota dailyQuota;
    private MockLedgerReplaceExecutor executor;
    private JobOperator jobOperator;
    private MockLedgerReplaceJobFactory jobFactory;
    private BatchSuccessStore successStore;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(LedgerMapper.class);
        dailyQuota = mock(BuildingLedgerDailyQuota.class);
        when(dailyQuota.remaining()).thenReturn(100L);
        executor = mock(MockLedgerReplaceExecutor.class);
        successStore = mock(BatchSuccessStore.class);

        jobFactory = new MockLedgerReplaceJobFactory(ledgerMapper, dailyQuota, executor, CHUNK_SIZE);
        jobOperator = DedicatedJobOperators.create(jobFactory.jobRepository(), "Mock 대장 교체 배치");
    }

    @Test
    @DisplayName("관심 매물 먼저 · 청크를 넘어 대상 전부를 처리하고 결과별로 집계한다 — 경합 · 락 오류가 있어도 계속한다")
    void runsAllAndAggregates() {
        page(true, null, 4L);
        page(false, null, 1L, 2L);
        page(false, 2L, 3L, 4L);
        page(false, 4L, 5L, 6L);
        page(false, 6L);
        when(executor.replaceAndAnalyze(4L))
                .thenReturn(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.FETCHED, true));
        when(executor.replaceAndAnalyze(1L))
                .thenReturn(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.NOT_FOUND, true));
        when(executor.replaceAndAnalyze(2L))
                .thenReturn(MockLedgerReplaceAttempt.failed(LedgerReplacementOutcome.FETCHED, analyzeFailure()));
        when(executor.replaceAndAnalyze(3L))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(executor.replaceAndAnalyze(5L)).thenThrow(new QueryTimeoutException("redis"));
        when(executor.replaceAndAnalyze(6L))
                .thenReturn(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.NOT_MOCK, false));

        MockLedgerReplaceReport report = launcher().run(DATE);

        // 관심 매물 4 는 전체 단계에서 다시 처리하지 않는다.
        verify(executor, times(1)).replaceAndAnalyze(4L);
        verify(executor, times(6)).replaceAndAnalyze(any());
        assertThat(report.summary()).isEqualTo("대상 6건 · 교체 2건 · 삭제 1건 · 재분석 2건 · 상한 0건 · 건너뜀 1건 · 실패 2건");
    }

    @Test
    @DisplayName("상한에 닿으면 남은 대상이 있어도 다음 청크를 읽지 않고 그날 회차를 끝낸다")
    void stopsWhenQuotaExhausted() {
        page(true, null);
        page(false, null, 1L, 2L);
        page(false, 2L, 3L);
        AtomicLong remaining = new AtomicLong(1);
        when(dailyQuota.remaining()).thenAnswer(invocation -> remaining.get());
        when(executor.replaceAndAnalyze(1L)).thenAnswer(invocation -> {
            remaining.set(0);
            return MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.FETCHED, true);
        });
        // 같은 청크로 이미 읽힌 매물은 처리 단계를 지나지만, 실행기가 상한을 보고 떼지 않는다.
        when(executor.replaceAndAnalyze(2L))
                .thenReturn(MockLedgerReplaceAttempt.completed(LedgerReplacementOutcome.QUOTA_EXHAUSTED, false));

        MockLedgerReplaceReport report = launcher().run(DATE);

        verify(executor, never()).replaceAndAnalyze(3L);
        assertThat(report.getTargets()).isEqualTo(2);
        assertThat(report.getReplaced()).isEqualTo(1);
        assertThat(report.getQuotaExhausted()).isEqualTo(1);
    }

    @Test
    @DisplayName("대상이 없으면 매물 처리 없이 모든 건수가 0 이다")
    void noTargets() {
        page(true, null);
        page(false, null);

        MockLedgerReplaceReport report = launcher().run(DATE);

        verify(executor, never()).replaceAndAnalyze(any());
        assertThat(report.summary()).isEqualTo("대상 0건 · 교체 0건 · 삭제 0건 · 재분석 0건 · 상한 0건 · 건너뜀 0건 · 실패 0건");
    }

    @Test
    @DisplayName("대상 조회가 실패하면 스텝이 멈추고 예외로 알린다")
    void readerFailurePropagates() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(ledgerMapper.selectPropertyIdsByLedgerSource(any())).thenThrow(failure);

        assertThatThrownBy(() -> launcher().run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCause(failure);
    }

    @Test
    @DisplayName("회차가 COMPLETED 로 끝나면 그 날짜의 성공 기록을 남긴다")
    void recordsSuccessWhenCompleted() {
        page(true, null);
        page(false, null);

        launcher().run(DATE);

        verify(successStore).markSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, DATE);
    }

    @Test
    @DisplayName("회차가 완료되지 못하면 성공 기록을 남기지 않는다 — 다음 기동이 다시 돌 수 있게")
    void noRecordWhenNotCompleted() {
        when(ledgerMapper.selectPropertyIdsByLedgerSource(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> launcher().run(DATE)).isInstanceOf(IllegalStateException.class);
        verify(successStore, never()).markSucceeded(any(), any());
    }

    @Test
    @DisplayName("Job 저장소는 이 배치 전용이다 — 기동 뒤 따라잡기로 등기 재조회 배치와 한 프로세스에서 겹쳐도 실행 기록을 나눠 쓰지 않는다")
    void usesDedicatedJobRepository() {
        MockLedgerReplaceJobFactory another =
                new MockLedgerReplaceJobFactory(ledgerMapper, dailyQuota, executor, CHUNK_SIZE);

        assertThat(jobFactory.jobRepository()).isNotNull().isNotSameAs(another.jobRepository());
    }

    @Test
    @DisplayName("배치 진입점에 날짜 키 분산 락이 대기 0 · 설정 만료로 붙어 있고, 키는 risk:batch:mock-ledger-replace:{yyyy-MM-dd} 로 풀린다")
    void dateLockAnnotation() throws NoSuchMethodException {
        Method run = MockLedgerReplaceJobLauncher.class.getMethod("run", LocalDate.class);
        DistributedLock lock = run.getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo("${risk.batch.mock-ledger-replace.lock-lease-time}");

        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                launcher(), run, new Object[] {DATE}, new DefaultParameterNameDiscoverer());
        String key = new SpelExpressionParser().parseExpression(lock.key()).getValue(context, String.class);
        assertThat(key).isEqualTo("risk:batch:mock-ledger-replace:2026-10-01");
    }

    private MockLedgerReplaceJobLauncher launcher() {
        return new MockLedgerReplaceJobLauncher(jobOperator, jobFactory, successStore, AT_0430);
    }

    private void page(boolean wishlistedOnly, Long lastPropertyId, Long... propertyIds) {
        when(ledgerMapper.selectPropertyIdsByLedgerSource(new LedgerSourceTargetCondition(
                LedgerDataSource.MOCK, wishlistedOnly, lastPropertyId, CHUNK_SIZE)))
                .thenReturn(List.of(propertyIds));
    }

    private static BusinessException analyzeFailure() {
        return new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
