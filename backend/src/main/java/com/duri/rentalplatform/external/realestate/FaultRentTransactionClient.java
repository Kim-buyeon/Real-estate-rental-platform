package com.duri.rentalplatform.external.realestate;

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
 * 전월세 실거래가 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 응답을 돌려준다.
 *
 * <p>정상 응답을 Mock 에 위임하는 이유는 하나다. Fault 가 자체 데이터를 들고 있으면 「지연은 있었지만
 * 값은 정상」인 경로를 Mock 경로와 다른 데이터로 검증하게 된다.
 *
 * <p><b>Real 과 같은 격리를 건다.</b> 재시도 · 서킷 · 폴백이 Real 에만 붙어 있으면 이 구현으로는
 * 「연속 실패 뒤 서킷이 열리는가」 · 「열린 동안 폴백이 나가는가」를 확인할 수 없다. 그것이 Fault 를
 * 두는 이유이므로 인스턴스 이름 · 폴백 동작을 Real 과 같게 맞춘다.
 */
@Component
@ConditionalOnProperty(prefix = "external.rent-transaction", name = "mode", havingValue = "fault")
public class FaultRentTransactionClient implements RentTransactionClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockRentTransactionClient delegate = new MockRentTransactionClient();

    public FaultRentTransactionClient(ExternalApiProperties properties) {
        this.settings = properties.rentTransaction();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
        FaultInjection.inject(settings);
        return delegate.findRentTransactions(query);
    }

    /**
     * 폴백. Real 과 같은 것을 던진다 — 빈 목록을 돌려주면 「거래가 없었다」와 구분되지 않는다.
     */
    @SuppressWarnings("unused")
    private List<RentTransaction> unavailable(RentTransactionQuery query, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
