package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
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
 * 관심 매물 등기 재조회 배치(RISK-08)의 한 회차 실행. 누군가 관심 매물로 등록한 매물의 등기를 하루 한 번 다시 떼어, 바뀌었거나 분석이
 * 뒤처진 매물을 재분석한다. 등급 변경 알림은 재분석이 발행하는 이벤트의 몫이다. 스텝 구성은 {@link RegistryRefreshJobFactory}.
 *
 * <p><b>하루 한 번</b> — 앱이 두 프로세스로 뜨고 둘 다 같은 시각에 스케줄이 돈다. 날짜 키 {@code risk:batch:registry-refresh:
 * {yyyy-MM-dd}} 의 분산 락을 기다리지 않고 한 번만 시도해, 못 잡은 인스턴스는 Job 을 시작하지 않는다. 만료는 하루보다 짧게 둔다 —
 * 끝나도 해제되므로 만료는 잡은 인스턴스가 죽었을 때만 쓰이고, 다음 날 같은 시각에는 반드시 풀려 있어야 한다. 날짜를 인자로 받는
 * 이유는 락 키가 인자로만 만들어지기 때문이다. Job 을 락 안에서 동기로 실행하므로 락은 스텝이 끝날 때까지 유지된다.
 *
 * <p><b>Job 파라미터</b> — 날짜와 실행 시각을 둘 다 식별 파라미터로 넣는다. 날짜만 넣으면 같은 날 두 번째 실행(수동 재실행 ·
 * 락 해제 뒤 다른 인스턴스)이 「이미 완료된 Job 인스턴스」로 거절된다. 실행 여부는 날짜 락이 가르므로 Batch 의 중복 판정에 기대지
 * 않는다. resourceless 저장소는 한 프로세스에서 동시에 한 Job 만 다룰 수 있고, 날짜 락과 하루 한 번의 스케줄이 그 조건을 지킨다.
 *
 * <p><b>대상 · 매물 단위</b> — 모니터링 여부와 무관하게 관심 매물 전부이고, 여러 사용자가 같은 매물을 등록해도 한 번만 처리한다.
 * 락 경합은 건너뛰고 실패는 기록한 뒤 다음 매물로 넘어간다 — 서킷이 열리면 나머지는 폴백으로 빠르게 실패하므로 전체를 멈추지 않는다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 매물마다 외부 호출이 있고 재조회 · 분석 서비스가 각자 경계를 긋는다.
 */
@Slf4j
@Component
public class RegistryRefreshJobLauncher {

    static final String DATE_PARAMETER = "date";
    static final String LAUNCHED_AT_PARAMETER = "launchedAt";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobOperator jobOperator;
    private final RegistryRefreshJobFactory jobFactory;
    private final Clock clock;

    @Autowired
    public RegistryRefreshJobLauncher(JobOperator jobOperator, RegistryRefreshJobFactory jobFactory) {
        this(jobOperator, jobFactory, Clock.system(SEOUL));
    }

    RegistryRefreshJobLauncher(JobOperator jobOperator, RegistryRefreshJobFactory jobFactory, Clock clock) {
        this.jobOperator = jobOperator;
        this.jobFactory = jobFactory;
        this.clock = clock;
    }

    /**
     * 그날의 배치를 한 번 돌린다. 스텝이 끝나면 집계를 한 줄 남긴다 — 스텝이 중간에 실패해도 그때까지의 건수가 남는다.
     *
     * @param date 실행 날짜(서울). 락 키가 된다
     * @return 회차 집계
     * @throws BusinessException     {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 같은 날짜의 락을 다른 인스턴스가 잡고 있을 때.
     *                               이 경우 아무것도 하지 않았다
     * @throws IllegalStateException Job 을 시작하지 못했거나 스텝이 완료되지 못했을 때(대상 조회 실패 등). 원인 예외를 담는다
     */
    @DistributedLock(
            key = "'risk:batch:registry-refresh:' + #date",
            waitTimeout = "0s",
            leaseTime = "${risk.batch.registry-refresh.lock-lease-time}")
    public RegistryRefreshReport run(LocalDate date) {
        log.info("[등기 재조회 배치] 시작 — {}", date);
        RegistryRefreshReport report = new RegistryRefreshReport();
        JobParameters parameters = new JobParametersBuilder()
                .addLocalDate(DATE_PARAMETER, date)
                .addLocalDateTime(LAUNCHED_AT_PARAMETER, LocalDateTime.now(clock.withZone(SEOUL)))
                .toJobParameters();

        JobExecution execution;
        try {
            execution = jobOperator.start(jobFactory.create(report), parameters);
        } catch (JobExecutionException e) {
            throw new IllegalStateException("등기 재조회 배치를 시작하지 못했다 — " + date, e);
        }

        BatchStatus status = execution.getStatus();
        log.info("[등기 재조회 배치] 종료 — {} · {} · {}", date, status, report.summary());
        if (status != BatchStatus.COMPLETED) {
            List<Throwable> failures = execution.getAllFailureExceptions();
            throw new IllegalStateException("등기 재조회 배치가 완료되지 못했다 — " + date + " · " + status,
                    failures.isEmpty() ? null : failures.getFirst());
        }
        return report;
    }
}
