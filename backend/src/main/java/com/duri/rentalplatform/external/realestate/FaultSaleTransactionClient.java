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
 * 매매 실거래가 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 응답을 돌려준다.
 *
 * <p>정상 응답을 Mock 에 위임하는 이유와 Real 과 같은 격리(인스턴스 이름 · 폴백)를 거는 이유는 전월세 Fault
 * ({@link FaultRentTransactionClient})와 같다.
 */
@Component
@ConditionalOnProperty(prefix = "external.sale-transaction", name = "mode", havingValue = "fault")
public class FaultSaleTransactionClient implements SaleTransactionClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockSaleTransactionClient delegate = new MockSaleTransactionClient();

    public FaultSaleTransactionClient(ExternalApiProperties properties) {
        this.settings = properties.saleTransaction();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<SaleTransaction> findSaleTransactions(SaleTransactionQuery query) {
        FaultInjection.inject(settings);
        return delegate.findSaleTransactions(query);
    }

    /**
     * 폴백. Real 과 같은 것을 던진다 — 빈 목록을 돌려주면 「거래가 없었다」와 구분되지 않는다.
     */
    @SuppressWarnings("unused")
    private List<SaleTransaction> unavailable(SaleTransactionQuery query, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
