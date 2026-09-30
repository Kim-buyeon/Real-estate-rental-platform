package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
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
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 매물 갱신 배치(RISK-08)의 한 회차 실행. 실거래가를 다시 모아 새 매물을 저장하고 바뀐 시세를 반영한 뒤, 시세가 바뀐 매물은
 * 재분석하고 최신 판정이 없는 매물은 판정한다 — 데이터 적재 설계서 1.5 「매물 · 시세 일 1회」. 등급 변경 알림은 판정이 발행하는
 * 이벤트의 몫이다. 스텝 구성은 {@link PropertyRefreshJobFactory}.
 *
 * <p><b>하루 한 번</b> — 날짜 키 {@code property:batch:refresh:{yyyy-MM-dd}} 의 분산 락을 기다리지 않고 한 번만 시도해, 못 잡은
 * 인스턴스는 Job 을 시작하지 않는다. 만료 · 날짜 인자 · 락 유지의 이유는 {@link RegistryRefreshJobLauncher} 와 같다. 등기 재조회
 * 배치와 키가 다르므로 두 배치는 서로의 날짜 락을 막지 않는다 — 같은 매물에서 겹치면 매물 분석 락이 한쪽을 건너뛰게 한다.
 *
 * <p><b>Job 파라미터</b> — 날짜와 실행 시각을 둘 다 식별 파라미터로 넣는다. 이유는 {@link RegistryRefreshJobLauncher} 와 같다.
 *
 * <p><b>전용 Job 실행기</b> — 앱의 Job 실행기 빈이 아니라 이 배치 전용 저장소({@link PropertyRefreshJobFactory#jobRepository()})로
 * 만든 실행기를 쓴다. 등기 재조회 배치와 한 프로세스에서 겹쳐도 저장소를 나눠 쓰지 않게 하기 위해서다 — 이유는 그 클래스 주석.
 * 실행은 동기다 — 락 안에서 Job 이 끝나야 날짜 락이 스텝 끝까지 유지된다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 적재는 외부 호출을 포함하고, 적재 쓰기 · 판정 서비스가 각자 경계를 긋는다.
 */
@Slf4j
@Component
public class PropertyRefreshJobLauncher {

    static final String DATE_PARAMETER = "date";
    static final String LAUNCHED_AT_PARAMETER = "launchedAt";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobOperator jobOperator;
    private final PropertyRefreshJobFactory jobFactory;
    private final BatchSuccessStore successStore;
    private final Clock clock;

    @Autowired
    public PropertyRefreshJobLauncher(PropertyRefreshJobFactory jobFactory, BatchSuccessStore successStore) {
        this(dedicatedJobOperator(jobFactory.jobRepository()), jobFactory, successStore, Clock.system(SEOUL));
    }

    PropertyRefreshJobLauncher(
            JobOperator jobOperator, PropertyRefreshJobFactory jobFactory, BatchSuccessStore successStore, Clock clock) {
        this.jobOperator = jobOperator;
        this.jobFactory = jobFactory;
        this.successStore = successStore;
        this.clock = clock;
    }

    /** 전용 저장소로 동기 Job 실행기를 만든다. Job 을 등록해 두지 않으므로 등록부는 비어 있다. */
    static JobOperator dedicatedJobOperator(JobRepository jobRepository) {
        TaskExecutorJobOperator jobOperator = new TaskExecutorJobOperator();
        jobOperator.setJobRepository(jobRepository);
        jobOperator.setJobRegistry(new MapJobRegistry());
        jobOperator.setTaskExecutor(new SyncTaskExecutor());
        try {
            jobOperator.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("매물 갱신 배치의 Job 실행기를 만들지 못했다", e);
        }
        return jobOperator;
    }

    /**
     * 그날의 배치를 한 번 돌린다. 끝나면 집계를 남긴다 — 스텝이 중간에 실패해도 그때까지의 건수와 적재 실패 사유가 남는다.
     *
     * @param date 실행 날짜(서울). 락 키가 된다
     * @return 회차 집계
     * @throws BusinessException     {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 같은 날짜의 락을 다른 인스턴스가 잡고 있을 때.
     *                               이 경우 아무것도 하지 않았다
     * @throws IllegalStateException Job 을 시작하지 못했거나 스텝이 완료되지 못했을 때. 원인 예외를 담는다
     */
    @DistributedLock(
            key = "'property:batch:refresh:' + #date",
            waitTimeout = "0s",
            leaseTime = "${property.batch.refresh.lock-lease-time}")
    public PropertyRefreshReport run(LocalDate date) {
        log.info("[매물 갱신 배치] 시작 — {}", date);
        PropertyRefreshReport report = new PropertyRefreshReport();
        JobParameters parameters = new JobParametersBuilder()
                .addLocalDate(DATE_PARAMETER, date)
                .addLocalDateTime(LAUNCHED_AT_PARAMETER, LocalDateTime.now(clock.withZone(SEOUL)))
                .toJobParameters();

        JobExecution execution;
        try {
            execution = jobOperator.start(jobFactory.create(report), parameters);
        } catch (JobExecutionException e) {
            throw new IllegalStateException("매물 갱신 배치를 시작하지 못했다 — " + date, e);
        }

        BatchStatus status = execution.getStatus();
        log.info("[매물 갱신 배치] 종료 — {} · {} · {}", date, status, report.summary());
        report.getLoadReport().getFailures().forEach(failure -> log.warn("[매물 갱신 배치] 적재 실패 — {}", failure));
        if (status != BatchStatus.COMPLETED) {
            List<Throwable> failures = execution.getAllFailureExceptions();
            throw new IllegalStateException("매물 갱신 배치가 완료되지 못했다 — " + date + " · " + status,
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
            successStore.markSucceeded(DailyBatch.PROPERTY_REFRESH, date);
        } catch (RuntimeException e) {
            log.warn("[매물 갱신 배치] 성공 기록을 남기지 못했다 — {}", date, e);
        }
    }
}
