package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.service.PropertyLoadService;
import com.duri.rentalplatform.domain.risk.service.PropertyRefreshAnalysisExecutor;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
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
 * 매물 갱신 배치(RISK-08)의 Job 조립. 두 스텝을 차례로 돈다.
 *
 * <ol>
 *   <li>적재 — {@link PropertyRefreshLoadTasklet}. 새 매물 저장 · 바뀐 시세 갱신</li>
 *   <li>판정 — 청크. 읽기 {@link PropertyRefreshTargetReader} · 처리 {@link PropertyRefreshItemProcessor} · 쓰기
 *       {@link PropertyRefreshReportWriter}. 청크 크기는 대상 식별자 페이지 크기와 같다</li>
 * </ol>
 *
 * <p>적재 스텝이 실패하면 Job 이 멈추고 판정 스텝은 돌지 않는다. 판정은 적재가 남긴 시세 변경 식별자를 이어받기 때문이다.
 *
 * <p><b>전용 저장소</b> — 앱이 가진 Job 저장소 빈을 쓰지 않고 이 배치만의 resourceless 저장소를 둔다. resourceless 저장소는 Job
 * 인스턴스 · 실행을 하나만 들고 스레드 안전하지 않다(Spring Batch 6.0.5 {@code ResourcelessJobRepository} 주석). 이 배치가
 * 길어져 같은 인스턴스에서 등기 재조회 배치(03:00)와 겹치면, 저장소를 나눠 쓰는 두 Job 이 서로의 실행 기록을 덮는다. 저장소를
 * 나누면 겹쳐도 각자의 기록을 본다. 이 배치끼리의 겹침은 날짜 락과 하루 한 번의 스케줄이 막는다.
 *
 * <p><b>회차마다 만드는 이유 · 스텝 트랜잭션 · 판정 스텝의 동기화를 끄는 이유</b>는 {@link RegistryRefreshJobFactory} 와 같다.
 * 적재 스텝도 resourceless 관리자로 돈다 — JPA 관리자로 두면 스텝 전체가 한 DB 트랜잭션이 되어 25개 자치구의 외부 호출이 그 안에
 * 든다. 저장 경계는 적재 쓰기 서비스가 덩어리마다 긋는다. 적재 스텝만 동기화를 켜 두는 이유는 {@link #create} 안의 주석.
 */
@Component
public class PropertyRefreshJobFactory {

    public static final String JOB_NAME = "propertyRefreshJob";
    public static final String LOAD_STEP_NAME = "propertyRefreshLoadStep";
    public static final String ANALYSIS_STEP_NAME = "propertyRefreshAnalysisStep";

    /** 이 배치 전용 저장소 — 클래스 주석 「전용 저장소」. */
    private final JobRepository jobRepository = new ResourcelessJobRepository();
    private final PropertyLoadService propertyLoadService;
    private final PropertyMapper propertyMapper;
    private final PropertyRefreshAnalysisExecutor executor;
    private final int months;
    private final int chunkSize;

    public PropertyRefreshJobFactory(
            PropertyLoadService propertyLoadService,
            PropertyMapper propertyMapper,
            PropertyRefreshAnalysisExecutor executor,
            @Value("${property.batch.refresh.months}") int months,
            @Value("${property.batch.refresh.page-size}") int chunkSize) {
        this.propertyLoadService = propertyLoadService;
        this.propertyMapper = propertyMapper;
        this.executor = executor;
        this.months = months;
        this.chunkSize = chunkSize;
    }

    /** 이 배치 전용 저장소. 배치 시작기가 이 저장소로 Job 실행기를 만든다. */
    public JobRepository jobRepository() {
        return jobRepository;
    }

    /**
     * 한 회차의 Job 을 만든다.
     *
     * @param report 이 회차의 집계. 적재 스텝과 판정 스텝의 쓰기 단계가 채운다
     */
    public Job create(PropertyRefreshReport report) {
        // 적재 스텝은 동기화를 켠 채 둔다. 태스크릿 스텝은 스텝 트랜잭션에 자기 동기화 콜백을 등록하므로, 끄면 「Transaction
        // synchronization is not active」로 스텝이 실패한다(Spring Batch 6.0.5 TaskletStep). 켜 두어도 JPA 쓰기 경계는 적재 쓰기
        // 서비스가 덩어리마다 새로 연다 — JPA 관리자는 resourceless 트랜잭션을 기존 트랜잭션으로 보지 않는다. 적재 스텝은 MyBatis
        // 를 쓰지 않으므로 아래 판정 스텝처럼 세션 · 커넥션이 스텝 동안 묶일 자리도 없다.
        ResourcelessTransactionManager loadTransactionManager = new ResourcelessTransactionManager();

        ResourcelessTransactionManager analysisTransactionManager = new ResourcelessTransactionManager();
        analysisTransactionManager.setTransactionSynchronization(
                AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);

        Step loadStep = new StepBuilder(LOAD_STEP_NAME, jobRepository)
                .tasklet(new PropertyRefreshLoadTasklet(propertyLoadService, months, report), loadTransactionManager)
                .build();

        Step analysisStep = new StepBuilder(ANALYSIS_STEP_NAME, jobRepository)
                .<PropertyRefreshTarget, PropertyRefreshAttempt>chunk(chunkSize)
                .reader(new PropertyRefreshTargetReader(propertyMapper, report, chunkSize))
                .processor(new PropertyRefreshItemProcessor(executor))
                .writer(new PropertyRefreshReportWriter(report))
                .transactionManager(analysisTransactionManager)
                .build();

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(loadStep)
                .next(analysisStep)
                .build();
    }
}
