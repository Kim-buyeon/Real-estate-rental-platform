package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import com.duri.rentalplatform.domain.risk.service.RegistryRefreshBatchExecutor;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * 관심 매물 등기 재조회 배치(RISK-08)의 Job 조립. 한 스텝 청크 — 읽기 {@link WishlistedPropertyIdReader} · 처리
 * {@link RegistryRefreshItemProcessor} · 쓰기 {@link RegistryRefreshReportWriter}. 청크 크기는 대상 식별자 페이지 크기와 같다.
 *
 * <p><b>Job 을 빈으로 두지 않고 회차마다 만드는 이유</b> — 읽기 단계는 커서를, 쓰기 단계는 집계를 필드로 갖는다. 빈으로 두면 회차가
 * 그 상태를 나눠 쓰고, 실행 컨텍스트로 넘기는 방법은 resourceless 저장소가 보관하지 않아 쓸 수 없다. 회차마다 새 읽기 · 쓰기 ·
 * 집계로 조립하면 상태가 회차 하나에 묶인다. Job 빈이 없으므로 기동 시 자동 실행 대상도 없다 — 그래도 설정
 * {@code spring.batch.job.enabled} 는 끈다.
 *
 * <p><b>스텝 트랜잭션</b> — {@link ResourcelessTransactionManager} 다. JPA 트랜잭션 관리자를 쓰면 청크 하나가 DB 트랜잭션이
 * 되어 매물마다의 외부 등기 조회가 그 안에 든다 — {@code backend/CLAUDE.md} Service. 저장 경계는 처리 단계 안의 재조회 · 분석
 * 서비스가 각자 긋는다. 이 관리자는 빈으로 등록하지 않으므로 JPA {@code transactionManager} 빈과 겹치지 않는다.
 *
 * <p><b>트랜잭션 동기화를 끄는 이유</b> — 청크 트랜잭션이 동기화를 켜면 읽기 단계의 MyBatis 세션과 그 커넥션이 청크가 끝날 때까지
 * 스레드에 묶인다. 그러면 외부 호출이 도는 동안 커넥션 하나가 풀로 돌아가지 않는다. 동기화를 끄면 매퍼 호출마다 커넥션을 받고
 * 돌려주어, 배치를 Spring Batch 로 옮기기 전과 같다.
 */
@Component
public class RegistryRefreshJobFactory {

    public static final String JOB_NAME = "registryRefreshJob";
    public static final String STEP_NAME = "registryRefreshStep";

    private final JobRepository jobRepository;
    private final WishlistMapper wishlistMapper;
    private final RegistryRefreshBatchExecutor executor;
    private final int chunkSize;

    public RegistryRefreshJobFactory(
            JobRepository jobRepository,
            WishlistMapper wishlistMapper,
            RegistryRefreshBatchExecutor executor,
            @Value("${risk.batch.registry-refresh.page-size}") int chunkSize) {
        this.jobRepository = jobRepository;
        this.wishlistMapper = wishlistMapper;
        this.executor = executor;
        this.chunkSize = chunkSize;
    }

    /**
     * 한 회차의 Job 을 만든다.
     *
     * @param report 이 회차의 집계. 쓰기 단계가 채운다
     */
    public Job create(RegistryRefreshReport report) {
        ResourcelessTransactionManager transactionManager = new ResourcelessTransactionManager();
        transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);

        Step step = new StepBuilder(STEP_NAME, jobRepository)
                .<Long, RegistryRefreshAttempt>chunk(chunkSize)
                .reader(new WishlistedPropertyIdReader(wishlistMapper, chunkSize))
                .processor(new RegistryRefreshItemProcessor(executor))
                .writer(new RegistryRefreshReportWriter(report))
                .transactionManager(transactionManager)
                .build();

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(step)
                .build();
    }
}
