package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.service.PropertyRefreshAnalysisExecutor;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshAttempt;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * 매물 갱신 배치(RISK-08) 판정 스텝의 처리 단계. 대상 하나를 {@link PropertyRefreshAnalysisExecutor} 에 넘기고, 락 밖으로 나온
 * 예외까지 결과 값으로 바꾼다 — 매물 하나의 경합 · 실패가 회차를 멈추지 않게 한다. 이유는 {@link RegistryRefreshItemProcessor}
 * 와 같다.
 */
@Slf4j
public class PropertyRefreshItemProcessor implements ItemProcessor<PropertyRefreshTarget, PropertyRefreshAttempt> {

    private final PropertyRefreshAnalysisExecutor executor;

    public PropertyRefreshItemProcessor(PropertyRefreshAnalysisExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PropertyRefreshAttempt process(PropertyRefreshTarget target) {
        PropertyRefreshAttempt attempt;
        try {
            attempt = executor.analyze(target);
        } catch (RuntimeException e) {
            // 락 안의 실패는 결과에 담겨 오므로, 여기로 오는 503 은 락을 못 잡은 경우다.
            if (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                log.info("[매물 갱신 배치] 건너뜀 — 매물 {} 을 다른 요청이 처리 중", target.propertyId());
                return PropertyRefreshAttempt.lockContended(target);
            }
            // 락 획득 자체의 실패(Redis 장애 등). 이 매물만 실패로 두고 계속한다.
            log.warn("[매물 갱신 배치] 실패 — 매물 {} 락 획득 중 오류", target.propertyId(), e);
            return PropertyRefreshAttempt.failed(target, e);
        }

        if (attempt.isFailed()) {
            log.warn("[매물 갱신 배치] 실패 — 매물 {} 판정 (재분석 대기 {})", target.propertyId(), target.reanalysisPending(),
                    attempt.failure());
        }
        return attempt;
    }
}
