package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

/** {@link RegistryRefreshReportWriter} — 청크의 매물별 결과를 대상 · 변동 · 재분석 · 건너뜀 · 실패로 센다. */
class RegistryRefreshReportWriterTest {

    @Test
    @DisplayName("매물별 결과를 다섯 칸으로 센다 — 변동 뒤 재분석 실패는 변동과 실패에 함께 센다")
    void countsEachAttempt() {
        RegistryRefreshReport report = new RegistryRefreshReport();
        RegistryRefreshReportWriter writer = new RegistryRefreshReportWriter(report);

        writer.write(Chunk.of(
                RegistryRefreshAttempt.completed(RegistryRefreshOutcome.UNCHANGED, false),
                RegistryRefreshAttempt.completed(RegistryRefreshOutcome.CHANGED, true),
                RegistryRefreshAttempt.completed(RegistryRefreshOutcome.UNCHANGED, true),
                RegistryRefreshAttempt.failed(RegistryRefreshOutcome.CHANGED, failure()),
                RegistryRefreshAttempt.lockContended()));
        writer.write(Chunk.of(RegistryRefreshAttempt.failed(null, failure())));

        assertThat(report.getTargets()).isEqualTo(6);
        assertThat(report.getModified()).isEqualTo(2);
        assertThat(report.getReanalyzed()).isEqualTo(2);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailed()).isEqualTo(2);
    }

    private static BusinessException failure() {
        return new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
