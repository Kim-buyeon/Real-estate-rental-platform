package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * 매물 갱신 배치(RISK-08) 판정 스텝의 쓰기 단계. 저장은 처리 단계 안의 판정 서비스가 이미 끝냈으므로 결과를 회차 집계에 더하기만
 * 한다. 집계를 생성자로 받는 이유는 {@link RegistryRefreshReportWriter} 와 같다.
 */
public class PropertyRefreshReportWriter implements ItemWriter<PropertyRefreshAttempt> {

    private final PropertyRefreshReport report;

    public PropertyRefreshReportWriter(PropertyRefreshReport report) {
        this.report = report;
    }

    @Override
    public void write(Chunk<? extends PropertyRefreshAttempt> chunk) {
        for (PropertyRefreshAttempt attempt : chunk) {
            report.addAttempt(attempt);
        }
    }
}
