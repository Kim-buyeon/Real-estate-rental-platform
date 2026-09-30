package com.duri.rentalplatform.external.loanrate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 전세자금대출 금리 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 응답을 돌려준다.
 *
 * <p>Real 과 같은 재시도 · 서킷 · 폴백을 건다 — 인스턴스 이름이 같아야 Fault 로 연 서킷이 Real 의 서킷이다.
 */
@Component
@ConditionalOnProperty(prefix = "external.jeonse-loan-rate", name = "mode", havingValue = "fault")
public class FaultJeonseLoanRateClient implements JeonseLoanRateClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockJeonseLoanRateClient delegate = new MockJeonseLoanRateClient();

    public FaultJeonseLoanRateClient(ExternalApiProperties properties) {
        this.settings = properties.jeonseLoanRate();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<BankLoanRate> findBankLoanRates(LoanRateQuery query) {
        FaultInjection.inject(settings);
        return delegate.findBankLoanRates(query);
    }

    /** 폴백. Real 과 같은 것을 던진다 — 빈 목록을 돌려주면 「그 달 실적 없음」과 구분되지 않는다. */
    @SuppressWarnings("unused")
    private List<BankLoanRate> unavailable(LoanRateQuery query, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
