package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * 등기 재조회 배치(RISK-08) 스텝의 쓰기 단계. 저장은 처리 단계 안의 재조회 · 분석 서비스가 이미 끝냈으므로, 여기서는 매물별 결과를
 * 회차 집계에 더하기만 한다.
 *
 * <p><b>집계를 생성자로 받는 이유</b> — 회차가 끝난 뒤 부른 쪽이 같은 집계를 돌려줘야 한다. resourceless 저장소는 실행 컨텍스트를
 * 보관하지 않으므로 거기에 담아 넘기지 않는다. 빈이 아니며 회차마다 새 집계와 함께 만든다.
 */
public class RegistryRefreshReportWriter implements ItemWriter<RegistryRefreshAttempt> {

    private final RegistryRefreshReport report;

    public RegistryRefreshReportWriter(RegistryRefreshReport report) {
        this.report = report;
    }

    @Override
    public void write(Chunk<? extends RegistryRefreshAttempt> chunk) {
        for (RegistryRefreshAttempt attempt : chunk) {
            report.addTarget();
            if (attempt.skipped()) {
                report.addSkipped();
                continue;
            }
            if (attempt.modified()) {
                report.addModified();
            }
            if (attempt.reanalyzed()) {
                report.addReanalyzed();
            }
            if (attempt.isFailed()) {
                report.addFailed();
            }
        }
    }
}
