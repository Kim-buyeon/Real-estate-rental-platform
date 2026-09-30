package com.duri.rentalplatform.domain.risk.batch;

import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * 배치 전용 저장소로 동기 Job 실행기를 만든다. 앱의 Job 실행기 빈이 쓰는 resourceless 저장소는 Job 인스턴스 · 실행을 하나만 들고
 * 스레드 안전하지 않아(Spring Batch 6.0.5 {@code ResourcelessJobRepository} 주석), 한 프로세스에서 두 배치가 겹치면 서로의 실행
 * 기록을 덮는다. 배치마다 자기 저장소와 실행기를 두면 겹쳐도 각자의 기록을 본다. 실행은 동기다 — 날짜 락 안에서 Job 이 끝나야 락이
 * 스텝 끝까지 유지된다. Job 을 등록해 두지 않으므로 등록부는 비어 있다.
 */
final class DedicatedJobOperators {

    private DedicatedJobOperators() {
    }

    /**
     * @param jobRepository 그 배치 전용 저장소
     * @param batchLabel    실패 메시지에 넣을 배치 이름
     */
    static JobOperator create(JobRepository jobRepository, String batchLabel) {
        TaskExecutorJobOperator jobOperator = new TaskExecutorJobOperator();
        jobOperator.setJobRepository(jobRepository);
        jobOperator.setJobRegistry(new MapJobRegistry());
        jobOperator.setTaskExecutor(new SyncTaskExecutor());
        try {
            jobOperator.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(batchLabel + "의 Job 실행기를 만들지 못했다", e);
        }
        return jobOperator;
    }
}
