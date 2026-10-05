package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.domain.property.store.DistrictCountCacheStore;
import com.duri.rentalplatform.domain.risk.service.MockLedgerReplaceExecutor;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceReport;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * Mock 대장 교체 배치의 Job 조립. 한 스텝 청크 — 읽기 {@link MockLedgerTargetReader} · 처리
 * {@link MockLedgerReplaceItemProcessor} · 쓰기 {@link MockLedgerReplaceReportWriter}. 청크 크기는 대상 식별자 페이지 크기와 같다.
 *
 * <p>Job 을 회차마다 만드는 이유 · resourceless 스텝 트랜잭션 · 트랜잭션 동기화를 끄는 이유는 {@link RegistryRefreshJobFactory}
 * 와 같다 — 매물마다 외부 대장 조회가 있으므로 청크를 DB 트랜잭션으로 묶지 않는다.
 *
 * <p><b>전용 저장소</b> — 앱이 가진 Job 저장소 빈을 쓰지 않고 이 배치만의 resourceless 저장소를 둔다. 이유는
 * {@link PropertyRefreshJobFactory} 와 같다 — 기동 뒤 따라잡기가 아무 시각에나 돌아 같은 프로세스에서 등기 재조회 배치와 겹칠 수
 * 있고, 저장소를 나눠 쓰면 서로의 실행 기록을 덮는다. 이 배치끼리의 겹침은 날짜 락이 막는다.
 *
 * <p><b>끝나면 자치구 집계 캐시의 세대를 올린다</b>(#406) — 판정이 등급 분포를 바꾼다. 시점과 이유는
 * {@link DistrictCountGenerationBumpListener}.
 */
@Component
public class MockLedgerReplaceJobFactory {

    public static final String JOB_NAME = "mockLedgerReplaceJob";
    public static final String STEP_NAME = "mockLedgerReplaceStep";

    /** 이 배치 전용 저장소 — 클래스 주석 「전용 저장소」. */
    private final JobRepository jobRepository = new ResourcelessJobRepository();
    private final LedgerMapper ledgerMapper;
    private final BuildingLedgerDailyQuota dailyQuota;
    private final MockLedgerReplaceExecutor executor;
    private final DistrictCountCacheStore districtCountCacheStore;
    private final int chunkSize;

    public MockLedgerReplaceJobFactory(
            LedgerMapper ledgerMapper,
            BuildingLedgerDailyQuota dailyQuota,
            MockLedgerReplaceExecutor executor,
            DistrictCountCacheStore districtCountCacheStore,
            @Value("${risk.batch.mock-ledger-replace.page-size}") int chunkSize) {
        this.ledgerMapper = ledgerMapper;
        this.dailyQuota = dailyQuota;
        this.executor = executor;
        this.districtCountCacheStore = districtCountCacheStore;
        this.chunkSize = chunkSize;
    }

    /** 이 배치 전용 저장소. 배치 시작기가 이 저장소로 Job 실행기를 만든다. */
    public JobRepository jobRepository() {
        return jobRepository;
    }

    /**
     * 한 회차의 Job 을 만든다.
     *
     * @param report 이 회차의 집계. 쓰기 단계가 채운다
     */
    public Job create(MockLedgerReplaceReport report) {
        ResourcelessTransactionManager transactionManager = new ResourcelessTransactionManager();
        transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);

        Step step = new StepBuilder(STEP_NAME, jobRepository)
                .<Long, MockLedgerReplaceAttempt>chunk(chunkSize)
                .reader(new MockLedgerTargetReader(ledgerMapper, dailyQuota, chunkSize))
                .processor(new MockLedgerReplaceItemProcessor(executor))
                .writer(new MockLedgerReplaceReportWriter(report))
                .transactionManager(transactionManager)
                .build();

        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(new DistrictCountGenerationBumpListener(districtCountCacheStore))
                .start(step)
                .build();
    }
}
