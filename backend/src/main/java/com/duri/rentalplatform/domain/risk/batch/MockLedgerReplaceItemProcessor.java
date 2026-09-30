package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.service.MockLedgerReplaceExecutor;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceAttempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Mock 대장 교체 배치 스텝의 처리 단계. 매물 하나를 {@link MockLedgerReplaceExecutor} 에 넘기고, 락 밖으로 나온 예외까지 결과
 * 값으로 바꾼다 — {@link RegistryRefreshItemProcessor} 와 같은 이유다.
 */
@Slf4j
public class MockLedgerReplaceItemProcessor implements ItemProcessor<Long, MockLedgerReplaceAttempt> {

    private final MockLedgerReplaceExecutor executor;

    public MockLedgerReplaceItemProcessor(MockLedgerReplaceExecutor executor) {
        this.executor = executor;
    }

    @Override
    public MockLedgerReplaceAttempt process(Long propertyId) {
        MockLedgerReplaceAttempt attempt;
        try {
            attempt = executor.replaceAndAnalyze(propertyId);
        } catch (RuntimeException e) {
            // 락 안의 실패는 결과에 담겨 오므로, 여기로 오는 503 은 락을 못 잡은 경우다.
            if (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                log.info("[Mock 대장 교체 배치] 건너뜀 — 매물 {} 을 다른 요청이 처리 중", propertyId);
                return MockLedgerReplaceAttempt.lockContended();
            }
            log.warn("[Mock 대장 교체 배치] 실패 — 매물 {} 락 획득 중 오류", propertyId, e);
            return MockLedgerReplaceAttempt.failed(null, e);
        }

        if (attempt.isFailed()) {
            log.warn("[Mock 대장 교체 배치] 실패 — 매물 {} (대장 결과 {})", propertyId, attempt.outcome(),
                    attempt.failure());
        }
        return attempt;
    }
}
