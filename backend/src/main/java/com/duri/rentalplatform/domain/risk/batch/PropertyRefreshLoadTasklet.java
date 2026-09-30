package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.service.PropertyLoadService;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * 매물 갱신 배치(RISK-08)의 첫 스텝 — 실거래가를 다시 모아 새 매물을 저장하고 바뀐 시세를 갱신한다. 일은
 * {@link PropertyLoadService#refresh} 가 하고, 여기서는 결과 건수를 회차 집계에 남긴다. 다음 스텝은 대상을 DB 에서 읽으므로
 * 넘겨주는 것이 없다 — 식별자 목록을 넘기면 회차 동안 신규 매물 수만큼 쌓인다.
 *
 * <p><b>청크로 만들지 않는 이유</b> — 적재는 자치구 단위로 돈다. 시세가 같은 법정동 · 면적대 표본의 중앙값이라 한 자치구의
 * 거래가 한자리에 모여야 계산되기 때문이다(적재 서비스 주석). 건 단위 읽기 · 쓰기로 쪼갤 수 없다.
 *
 * <p><b>실패</b> — 적재 서비스가 달 · 건 · 저장 덩어리 · 자치구 단위로 실패를 삼키고 집계에 남긴다. 그래도 밖으로 나온 예외는
 * 스텝을 멈추고 회차를 실패로 알린다 — 적재가 어디까지 되었는지 모르는 채로 판정 단계를 돌리지 않는다.
 *
 * <p>빈이 아니며 회차마다 새 집계와 함께 만든다.
 */
public class PropertyRefreshLoadTasklet implements Tasklet {

    private final PropertyLoadService propertyLoadService;
    private final int months;
    private final PropertyRefreshReport report;

    public PropertyRefreshLoadTasklet(PropertyLoadService propertyLoadService, int months,
                                      PropertyRefreshReport report) {
        if (months < 1) {
            throw new IllegalArgumentException("적재 기간은 1개월 이상이어야 한다: " + months);
        }
        this.propertyLoadService = propertyLoadService;
        this.months = months;
        this.report = report;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        PropertyRefreshResult result = propertyLoadService.refresh(months, report.getLoadReport());
        report.recordLoad(result);
        return RepeatStatus.FINISHED;
    }
}
