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
import com.duri.rentalplatform.domain.property.dto.condition.WishlistedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.service.RegistryRefreshBatchExecutor;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * {@link RegistryRefreshJobLauncher} — 스프링 컨텍스트 없이 실제 Spring Batch 로 한 회차를 돌린다. 저장소는 앱과 같은
 * resourceless, 실행은 동기다. 대상 조회와 매물 단위 실행기만 목이다.
 *
 * <p>여기서 보는 것 — 청크를 넘어 대상 전부가 처리되는가, 매물별 결과가 회차 집계로 돌아오는가, 같은 날 재실행이 되는가, 스텝이
 * 실패하면 삼키지 않는가, 날짜 락이 붙어 있는가. 읽기 · 처리 · 쓰기 단계 각각의 분기는 {@code batch} 패키지의 단위 테스트가,
 * 두 인스턴스의 날짜 락 경합은 다중 인스턴스 테스트가 본다.
 */
class RegistryRefreshJobLauncherTest {

    private static final int CHUNK_SIZE = 2;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
    private static final Clock AT_0300 = Clock.fixed(Instant.parse("2026-09-13T18:00:00Z"), ZoneOffset.UTC);
    private static final Clock AT_0310 = Clock.fixed(Instant.parse("2026-09-13T18:10:00Z"), ZoneOffset.UTC);

    private WishlistMapper wishlistMapper;
    private RegistryRefreshBatchExecutor executor;
    private TaskExecutorJobOperator jobOperator;
    private RegistryRefreshJobFactory jobFactory;

    @BeforeEach
    void setUp() throws Exception {
        wishlistMapper = mock(WishlistMapper.class);
        executor = mock(RegistryRefreshBatchExecutor.class);

        ResourcelessJobRepository jobRepository = new ResourcelessJobRepository();
        jobOperator = new TaskExecutorJobOperator();
        jobOperator.setJobRepository(jobRepository);
        jobOperator.setJobRegistry(new MapJobRegistry());
        jobOperator.afterPropertiesSet();

        jobFactory = new RegistryRefreshJobFactory(jobRepository, wishlistMapper, executor, CHUNK_SIZE);
    }

    @Test
    @DisplayName("청크를 넘어 대상 전부를 처리하고, 매물별 결과를 회차 집계로 돌려준다 — 경합 · 락 오류가 있어도 계속한다")
    void runsAllChunksAndReturnsReport() {
        page(null, 1L, 2L);
        page(2L, 3L, 4L);
        page(4L, 5L);
        when(executor.refreshAndAnalyzeIfNeeded(1L))
                .thenReturn(RegistryRefreshAttempt.completed(RegistryRefreshOutcome.UNCHANGED, false));
        when(executor.refreshAndAnalyzeIfNeeded(2L))
                .thenReturn(RegistryRefreshAttempt.completed(RegistryRefreshOutcome.CHANGED, true));
        when(executor.refreshAndAnalyzeIfNeeded(3L))
                .thenReturn(RegistryRefreshAttempt.failed(RegistryRefreshOutcome.CHANGED, analyzeFailure()));
        when(executor.refreshAndAnalyzeIfNeeded(4L))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(executor.refreshAndAnalyzeIfNeeded(5L)).thenThrow(new QueryTimeoutException("redis"));

        RegistryRefreshReport report = service(AT_0300).run(DATE);

        verify(executor, times(5)).refreshAndAnalyzeIfNeeded(any());
        assertThat(report.getTargets()).isEqualTo(5);
        assertThat(report.getModified()).isEqualTo(2);
        assertThat(report.getReanalyzed()).isEqualTo(1);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailed()).isEqualTo(2);
    }

    @Test
    @DisplayName("대상이 없으면 매물 처리 없이 모든 건수가 0 이다")
    void noTargets() {
        page(null);

        RegistryRefreshReport report = service(AT_0300).run(DATE);

        verify(executor, never()).refreshAndAnalyzeIfNeeded(any());
        assertThat(report.summary()).isEqualTo("대상 0건 · 변동 0건 · 재분석 0건 · 건너뜀 0건 · 실패 0건");
    }

    @Test
    @DisplayName("같은 날짜라도 실행 시각이 다르면 다시 돌고, 회차마다 집계가 따로다")
    void sameDateRerunsWithNewLaunchTime() {
        page(null, 1L);
        when(executor.refreshAndAnalyzeIfNeeded(1L))
                .thenReturn(RegistryRefreshAttempt.completed(RegistryRefreshOutcome.UNCHANGED, false));

        RegistryRefreshReport first = service(AT_0300).run(DATE);
        RegistryRefreshReport second = service(AT_0310).run(DATE);

        verify(executor, times(2)).refreshAndAnalyzeIfNeeded(1L);
        assertThat(first).isNotSameAs(second);
        assertThat(first.getTargets()).isEqualTo(1);
        assertThat(second.getTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("날짜와 실행 시각이 모두 같으면 Batch 가 완료된 인스턴스로 거절한다 — 실행 시각을 파라미터에 넣는 이유")
    void identicalParametersAreRejected() {
        page(null);
        service(AT_0300).run(DATE);

        assertThatThrownBy(() -> service(AT_0300).run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @DisplayName("대상 조회가 실패하면 스텝이 멈추고 예외로 알린다 — 조용히 빈 회차로 끝나지 않는다")
    void readerFailurePropagates() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(wishlistMapper.selectWishlistedPropertyIds(any())).thenThrow(failure);

        assertThatThrownBy(() -> service(AT_0300).run(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCause(failure);
    }

    @Test
    @DisplayName("배치 진입점에 날짜 키 분산 락이 대기 0 · 설정 만료로 붙어 있고, 키는 risk:batch:registry-refresh:{yyyy-MM-dd} 로 풀린다")
    void dateLockAnnotation() throws NoSuchMethodException {
        Method run = RegistryRefreshJobLauncher.class.getMethod("run", LocalDate.class);
        DistributedLock lock = run.getAnnotation(DistributedLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.waitTimeout()).isEqualTo("0s");
        assertThat(lock.leaseTime()).isEqualTo("${risk.batch.registry-refresh.lock-lease-time}");

        // 관점과 같은 방식으로 평가한다 — 인자 이름 #date 가 읽히는지까지 확인한다.
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                service(AT_0300), run, new Object[] {DATE}, new DefaultParameterNameDiscoverer());
        String key = new SpelExpressionParser().parseExpression(lock.key()).getValue(context, String.class);
        assertThat(key).isEqualTo("risk:batch:registry-refresh:2026-09-14");
    }

    private RegistryRefreshJobLauncher service(Clock clock) {
        return new RegistryRefreshJobLauncher(jobOperator, jobFactory, clock);
    }

    private void page(Long lastPropertyId, Long... propertyIds) {
        when(wishlistMapper.selectWishlistedPropertyIds(new WishlistedPropertyCondition(lastPropertyId, CHUNK_SIZE)))
                .thenReturn(List.of(propertyIds));
    }

    private static BusinessException analyzeFailure() {
        return new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
