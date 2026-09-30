package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceReport;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Mock 대장 교체 배치 스텝의 쓰기 단계. 저장은 처리 단계가 이미 끝냈으므로 매물별 결과를 회차 집계에 더하기만 한다 —
 * {@link RegistryRefreshReportWriter} 와 같다.
 */
public class MockLedgerReplaceReportWriter implements ItemWriter<MockLedgerReplaceAttempt> {

    private final MockLedgerReplaceReport report;

    public MockLedgerReplaceReportWriter(MockLedgerReplaceReport report) {
        this.report = report;
    }

    @Override
    public void write(Chunk<? extends MockLedgerReplaceAttempt> chunk) {
        for (MockLedgerReplaceAttempt attempt : chunk) {
            report.addTarget();
            if (attempt.skipped()) {
                report.addSkipped();
                continue;
            }
            if (attempt.outcome() != null) {
                switch (attempt.outcome()) {
                    case FETCHED -> report.addReplaced();
                    case NOT_FOUND -> report.addRemoved();
                    case QUOTA_EXHAUSTED -> report.addQuotaExhausted();
                    case RATE_LIMITED -> report.addRateLimited();
                    case COLLECTED -> report.addCollected();
                    case NO_LEDGER -> report.addStillMissing();
                    case NOT_MOCK -> {
                        // 다른 경로가 먼저 바꿨다. 따로 세지 않는다 — 대상 수에만 든다.
                    }
                }
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
