package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.service.RegistryRefreshBatchExecutor;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshAttempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * 등기 재조회 배치(RISK-08) 스텝의 처리 단계. 매물 하나를 {@link RegistryRefreshBatchExecutor} 에 넘기고, 락 밖으로 나온 예외까지
 * 결과 값으로 바꾼다.
 *
 * <p><b>실행기를 그대로 처리 단계로 쓰지 않는 이유</b> — 매물 락은 실행기 빈의 프록시가 걸고, 락을 못 잡으면 관점이 예외를 던진다.
 * 스텝은 처리 단계의 예외를 받으면 청크를 되돌리고 회차를 멈추므로, 경합 하나로 나머지 매물이 처리되지 않는다. 그래서 여기서 받아
 * 「건너뜀」 · 「실패」 결과로 바꾼다. 락 안의 실패는 실행기가 이미 결과로 담아 온다.
 *
 * <p>매물 식별자가 결과에 없으므로 건너뜀 · 실패의 매물 단위 기록은 여기서 남긴다.
 */
@Slf4j
public class RegistryRefreshItemProcessor implements ItemProcessor<Long, RegistryRefreshAttempt> {

    private final RegistryRefreshBatchExecutor executor;

    public RegistryRefreshItemProcessor(RegistryRefreshBatchExecutor executor) {
        this.executor = executor;
    }

    @Override
    public RegistryRefreshAttempt process(Long propertyId) {
        RegistryRefreshAttempt attempt;
        try {
            attempt = executor.refreshAndAnalyzeIfNeeded(propertyId);
        } catch (RuntimeException e) {
            // 락 안의 실패는 결과에 담겨 오므로, 여기로 오는 503 은 락을 못 잡은 경우다.
            if (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                log.info("[등기 재조회 배치] 건너뜀 — 매물 {} 을 다른 요청이 처리 중", propertyId);
                return RegistryRefreshAttempt.lockContended();
            }
            // 락 획득 자체의 실패(Redis 장애 등). 이 매물만 실패로 두고 계속한다.
            log.warn("[등기 재조회 배치] 실패 — 매물 {} 락 획득 중 오류", propertyId, e);
            return RegistryRefreshAttempt.failed(null, e);
        }

        if (attempt.isFailed()) {
            log.warn("[등기 재조회 배치] 실패 — 매물 {} (재조회 결과 {})", propertyId, attempt.outcome(),
                    attempt.failure());
        }
        return attempt;
    }
}
