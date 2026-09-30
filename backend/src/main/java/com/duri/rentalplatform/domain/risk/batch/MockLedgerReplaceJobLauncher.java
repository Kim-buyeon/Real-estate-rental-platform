package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceReport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mock 대장 교체 배치의 한 회차 실행. 연동을 real 로 바꾸기 전에 저장된 Mock 대장을 건축HUB 대장으로 바꾸거나(못 떼면 지우고)
 * 재분석한다. 관심 매물 먼저, 그다음 식별자 순이며 건축HUB 일일 호출 상한이 남는 만큼만 한다 — 상한에 닿으면 그날은 끝이고 남은
 * 매물은 다음 날 회차가 이어 간다(Mock 집합에서 빠진 매물은 다시 읽히지 않는다). 스텝 구성은 {@link MockLedgerReplaceJobFactory}.
 *
 * <p><b>하루 한 번 · Job 파라미터 · 트랜잭션</b> — {@link RegistryRefreshJobLauncher} 와 같다. 날짜 키
 * {@code risk:batch:mock-ledger-replace:{yyyy-MM-dd}} 의 분산 락을 기다리지 않고 한 번만 시도하고, 날짜와 실행 시각을 식별
 * 파라미터로 넣으며, 트랜잭션은 열지 않는다.
 *
 * <p><b>전용 Job 실행기</b> — 앱의 Job 실행기 빈이 아니라 이 배치 전용 저장소({@link MockLedgerReplaceJobFactory#jobRepository()})로
 * 만든 실행기를 쓴다. 기동 뒤 따라잡기는 예약 시각과 무관하게 돌므로 같은 프로세스에서 등기 재조회 배치와 겹칠 수 있다 — 저장소를
 * 나눠 쓰면 서로의 실행 기록을 덮는다. 이유는 {@link DedicatedJobOperators}. 실행은 동기다.
 */
@Slf4j
@Component
public class MockLedgerReplaceJobLauncher {

    static final String DATE_PARAMETER = "date";
    static final String LAUNCHED_AT_PARAMETER = "launchedAt";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobOperator jobOperator;
    private final MockLedgerReplaceJobFactory jobFactory;
    private final BatchSuccessStore successStore;
    private final Clock clock;

    @Autowired
    public MockLedgerReplaceJobLauncher(MockLedgerReplaceJobFactory jobFactory, BatchSuccessStore successStore) {
        this(DedicatedJobOperators.create(jobFactory.jobRepository(), "Mock 대장 교체 배치"), jobFactory, successStore,
                Clock.system(SEOUL));
    }

    MockLedgerReplaceJobLauncher(
            JobOperator jobOperator, MockLedgerReplaceJobFactory jobFactory, BatchSuccessStore successStore, Clock clock) {
        this.jobOperator = jobOperator;
        this.jobFactory = jobFactory;
        this.successStore = successStore;
        this.clock = clock;
    }

    /**
     * 그날의 교체를 한 번 돌린다. 스텝이 끝나면 집계를 한 줄 남긴다.
     *
     * @param date 실행 날짜(서울). 락 키가 된다
     * @return 회차 집계
     * @throws BusinessException     {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 같은 날짜의 락을 다른 인스턴스가 잡고 있을 때.
     *                               이 경우 아무것도 하지 않았다
     * @throws IllegalStateException Job 을 시작하지 못했거나 스텝이 완료되지 못했을 때(대상 조회 실패 등). 원인 예외를 담는다
     */
    @DistributedLock(
            key = "'risk:batch:mock-ledger-replace:' + #date",
            waitTimeout = "0s",
            leaseTime = "${risk.batch.mock-ledger-replace.lock-lease-time}")
    public MockLedgerReplaceReport run(LocalDate date) {
        log.info("[Mock 대장 교체 배치] 시작 — {}", date);
        MockLedgerReplaceReport report = new MockLedgerReplaceReport();
        JobParameters parameters = new JobParametersBuilder()
                .addLocalDate(DATE_PARAMETER, date)
                .addLocalDateTime(LAUNCHED_AT_PARAMETER, LocalDateTime.now(clock.withZone(SEOUL)))
                .toJobParameters();

        JobExecution execution;
        try {
            execution = jobOperator.start(jobFactory.create(report), parameters);
        } catch (JobExecutionException e) {
            throw new IllegalStateException("Mock 대장 교체 배치를 시작하지 못했다 — " + date, e);
        }

        BatchStatus status = execution.getStatus();
        log.info("[Mock 대장 교체 배치] 종료 — {} · {} · {}", date, status, report.summary());
        if (status != BatchStatus.COMPLETED) {
            List<Throwable> failures = execution.getAllFailureExceptions();
            throw new IllegalStateException("Mock 대장 교체 배치가 완료되지 못했다 — " + date + " · " + status,
                    failures.isEmpty() ? null : failures.getFirst());
        }
        recordSuccess(date);
        return report;
    }

    /**
     * 성공 기록을 남긴다 — 기동 뒤 따라잡기가 오늘 회차를 다시 시작하지 않게 한다. 기록에 실패해도 회차는 이미 끝났으므로 예외를
     * 올리지 않는다. 기록이 없으면 다음 기동이 같은 날 한 번 더 돌 뿐이다(동시 실행은 날짜 락이 막는다).
     */
    private void recordSuccess(LocalDate date) {
        try {
            successStore.markSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, date);
        } catch (RuntimeException e) {
            log.warn("[Mock 대장 교체 배치] 성공 기록을 남기지 못했다 — {}", date, e);
        }
    }
}
