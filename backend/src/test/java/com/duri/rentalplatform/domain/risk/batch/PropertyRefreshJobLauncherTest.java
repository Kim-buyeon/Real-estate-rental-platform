package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.property.dto.condition.PriceChangedPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.service.PropertyLoadService;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.service.PropertyRefreshAnalysisExecutor;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * {@link PropertyRefreshJobLauncher} — 스프링 컨텍스트 없이 실제 Spring Batch 로 한 회차를 돌린다. 저장소 · Job 실행기는 운영과
 * 같은 이 배치 전용 resourceless · 동기다. 적재 서비스 · 대상 조회 · 매물 단위 실행기만 목이다.
 *
 * <p>여기서 보는 것 — 적재 뒤에 판정이 돈다, 적재가 넘긴 시세 변경 매물은 재분석 · 미판정 매물은 첫 판정으로 청크를 넘어 전부
 * 처리된다, 경합 · 실패가 회차를 멈추지 않는다, 적재가 실패하면 판정 없이 예외로 알린다, 같은 날 재실행이 된다, 날짜 락이 붙어
 * 있다. 날짜 락의 인스턴스 간 성립은 같은 관점을 쓰는 등기 재조회 배치의 다중 인스턴스 테스트가 본다.
 */
class PropertyRefreshJobLauncherTest {

    private static final int MONTHS = 12;
    private static final int CHUNK_SIZE = 2;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final Clock AT_0200 = Clock.fixed(Instant.parse("2026-09-29T17:00:00Z"), ZoneOffset.UTC);
    private static final Clock AT_0210 = Clock.fixed(Instant.parse("2026-09-29T17:10:00Z"), ZoneOffset.UTC);

    private PropertyLoadService propertyLoadService;
    private PropertyMapper propertyMapper;
    private PropertyRefreshAnalysisExecutor executor;
    private PropertyRefreshJobFactory jobFactory;
    private JobOperator jobOperator;
    private BatchSuccessStore successStore;

    @BeforeEach
    void setUp() {
        propertyLoadService = mock(PropertyLoadService.class);
        propertyMapper = mock(PropertyMapper.class);
        executor = mock(PropertyRefreshAnalysisExecutor.class);
        successStore = mock(BatchSuccessStore.class);
        jobFactory = new PropertyRefreshJobFactory(propertyLoadService, propertyMapper, executor, MONTHS, CHUNK_SIZE);
        jobOperator = PropertyRefreshJobLauncher.dedicatedJobOperator(jobFactory.jobRepository());
        when(executor.analyze(any())).thenAnswer(invocation -> PropertyRefreshAttempt.analyzed(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("적재 뒤 미판정 매물은 첫 판정을 먼저, 시세 변경 매물은 재분석을 뒤에 청크를 넘어 전부 처리하고 회차 집계로 돌려준다")
    void loadsThenAnalyzesAllTargets() {
        loadReturns(2, 1);
        priceChangedPage(null, 5L);
        page(null, 1L, 21L);
        page(21L, 22L);

        PropertyRefreshReport report = launcher(AT_0200).run(DATE);

        InOrder order = inOrder(propertyLoadService, executor);
        order.verify(propertyLoadService).refresh(eq(MONTHS), any());
        order.verify(executor).analyze(PropertyRefreshTarget.unanalyzed(1L));
        order.verify(executor).analyze(PropertyRefreshTarget.unanalyzed(21L));
        order.verify(executor).analyze(PropertyRefreshTarget.unanalyzed(22L));
        order.verify(executor).analyze(PropertyRefreshTarget.priceChanged(5L));
        assertThat(report.getNewProperties()).isEqualTo(2);
        assertThat(report.getPriceChangedProperties()).isEqualTo(1);
        assertThat(report.getAnalysisTargets()).isEqualTo(4);
        assertThat(report.getReanalyzed()).isEqualTo(1);
        assertThat(report.getFirstAnalyzed()).isEqualTo(3);
        assertThat(report.getSkipped()).isZero();
        assertThat(report.getFailed()).isZero();
    }

    @Test
    @DisplayName("매물 락 경합은 건너뜀, 판정 실패 · 락 오류는 실패로 세고 나머지는 계속 처리한다")
    void contentionAndFailuresDoNotStopTheRun() {
        loadReturns(0, 2);
        priceChangedPage(null, 1L, 2L);
        page(null, 3L, 4L);
        page(4L);
        when(executor.analyze(PropertyRefreshTarget.priceChanged(1L)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(executor.analyze(PropertyRefreshTarget.priceChanged(2L))).thenReturn(PropertyRefreshAttempt.failed(
                PropertyRefreshTarget.priceChanged(2L), new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE)));
        when(executor.analyze(PropertyRefreshTarget.unanalyzed(3L))).thenThrow(new QueryTimeoutException("redis"));

        PropertyRefreshReport report = launcher(AT_0200).run(DATE);

        verify(executor, times(4)).analyze(any());
        assertThat(report.getAnalysisTargets()).isEqualTo(4);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailed()).isEqualTo(2);
        assertThat(report.getFirstAnalyzed()).isEqualTo(1);
        assertThat(report.getReanalyzed()).isZero();
    }

    @Test
    @DisplayName("적재가 예외로 끝나면 판정 없이 예외로 알린다 — 적재가 어디까지 되었는지 모르는 채 판정하지 않는다")
    void loadFailureStopsBeforeAnalysis() {
        IllegalStateException failure = new IllegalStateException("load broke");
        when(propertyLoadService.refresh(anyInt(), any())).thenThrow(failure);

        assertThatThrownBy(() -> launcher(AT_0200).run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCause(failure);
        verify(executor, never()).analyze(any());
        verify(propertyMapper, never()).selectPriceChangedPropertyIds(any());
        verify(propertyMapper, never()).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("미판정 조회가 실패하면 스텝이 멈추고 예외로 알린다 — 조용히 빈 회차로 끝나지 않는다")
    void readerFailurePropagates() {
        loadReturns(0, 0);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(propertyMapper.selectUnanalyzedPropertyIds(any())).thenThrow(failure);

        assertThatThrownBy(() -> launcher(AT_0200).run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCause(failure);
    }

    @Test
    @DisplayName("같은 날짜라도 실행 시각이 다르면 다시 돌고, 회차마다 집계가 따로다")
    void sameDateRerunsWithNewLaunchTime() {
        loadReturns(0, 0);
        page(null, 1L);

        PropertyRefreshReport first = launcher(AT_0200).run(DATE);
        PropertyRefreshReport second = launcher(AT_0210).run(DATE);

        verify(executor, times(2)).analyze(PropertyRefreshTarget.unanalyzed(1L));
        assertThat(first).isNotSameAs(second);
        assertThat(first.getAnalysisTargets()).isEqualTo(1);
        assertThat(second.getAnalysisTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("날짜와 실행 시각이 모두 같으면 Batch 가 완료된 인스턴스로 거절한다 — 실행 시각을 파라미터에 넣는 이유")
    void identicalParametersAreRejected() {
        loadReturns(0, 0);
        page(null);
        launcher(AT_0200).run(DATE);

        assertThatThrownBy(() -> launcher(AT_0200).run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @DisplayName("회차가 COMPLETED 로 끝나면 그 날짜의 성공 기록을 남긴다")
    void recordsSuccessWhenCompleted() {
        loadReturns(0, 0);
        page(null);

        launcher(AT_0200).run(DATE);

        verify(successStore).markSucceeded(DailyBatch.PROPERTY_REFRESH, DATE);
    }

    @Test
    @DisplayName("회차가 완료되지 못하면 성공 기록을 남기지 않는다 — 다음 기동이 다시 돌 수 있게")
    void noRecordWhenNotCompleted() {
        when(propertyLoadService.refresh(anyInt(), any())).thenThrow(new IllegalStateException("load broke"));

        assertThatThrownBy(() -> launcher(AT_0200).run(DATE)).isInstanceOf(IllegalStateException.class);
        verify(successStore, never()).markSucceeded(any(), any());
    }

    @Test
    @DisplayName("배치 진입점에 날짜 키 분산 락이 대기 0 · 설정 만료 · 설정 연장 간격으로 붙어 있고, 키는 property:batch:refresh:{yyyy-MM-dd} 로 풀린다")
    void dateLockAnnotation() throws NoSuchMethodException {
        Method run = PropertyRefreshJobLauncher.class.getMethod("run", LocalDate.class);
        DistributedLock lock = run.getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo("${property.batch.refresh.lock-lease-time}");
        assertThat(lock.renewInterval()).isEqualTo("${property.batch.refresh.lock-renew-interval}");

        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                launcher(AT_0200), run, new Object[] {DATE}, new DefaultParameterNameDiscoverer());
        String key = new SpelExpressionParser().parseExpression(lock.key()).getValue(context, String.class);
        assertThat(key).isEqualTo("property:batch:refresh:2026-09-30");
    }

    @Test
    @DisplayName("Job 저장소는 이 배치 전용이다 — 등기 재조회 배치와 한 프로세스에서 겹쳐도 실행 기록을 나눠 쓰지 않는다")
    void usesDedicatedJobRepository() {
        PropertyRefreshJobFactory another =
                new PropertyRefreshJobFactory(propertyLoadService, propertyMapper, executor, MONTHS, CHUNK_SIZE);

        assertThat(jobFactory.jobRepository()).isNotNull().isNotSameAs(another.jobRepository());
    }

    private PropertyRefreshJobLauncher launcher(Clock clock) {
        return new PropertyRefreshJobLauncher(jobOperator, jobFactory, successStore, clock);
    }

    private void loadReturns(int newProperties, int priceChangedProperties) {
        when(propertyLoadService.refresh(eq(MONTHS), any()))
                .thenReturn(new PropertyRefreshResult(newProperties, priceChangedProperties));
    }

    /** 시세 변경 매물 한 페이지. 조회를 심지 않은 페이지는 목 기본값인 빈 목록이다. */
    private void priceChangedPage(Long lastPropertyId, Long... propertyIds) {
        when(propertyMapper.selectPriceChangedPropertyIds(
                new PriceChangedPropertyCondition(lastPropertyId, CHUNK_SIZE)))
                .thenReturn(List.of(propertyIds));
    }

    private void page(Long lastPropertyId, Long... propertyIds) {
        when(propertyMapper.selectUnanalyzedPropertyIds(new UnanalyzedPropertyCondition(lastPropertyId, CHUNK_SIZE)))
                .thenReturn(List.of(propertyIds));
    }
}
