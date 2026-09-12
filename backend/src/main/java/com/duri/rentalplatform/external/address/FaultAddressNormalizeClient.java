package com.duri.rentalplatform.external.address;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 주소 정규화 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 결과를 돌려준다.
 *
 * <p>재시도 · 서킷 · 폴백을 Real 과 같은 인스턴스로 건다. 이 구현으로 서킷이 열리는 것을 확인하지
 * 못하면 Fault 를 둘 이유가 없다.
 */
@Component
@ConditionalOnProperty(prefix = "external.address-normalize", name = "mode", havingValue = "fault")
public class FaultAddressNormalizeClient implements AddressNormalizeClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockAddressNormalizeClient delegate = new MockAddressNormalizeClient();

    public FaultAddressNormalizeClient(ExternalApiProperties properties) {
        this.settings = properties.addressNormalize();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public Optional<NormalizedAddress> normalize(String rawAddress) {
        FaultInjection.inject(settings);
        return delegate.normalize(rawAddress);
    }

    /**
     * 폴백. 빈 값을 돌려주지 않는다 — 빈 값은 「그런 주소가 없다」는 뜻이라 적재가 그 매물을 조용히
     * 버리고 실패 건수에도 남지 않는다.
     */
    @SuppressWarnings("unused")
    private Optional<NormalizedAddress> unavailable(String rawAddress, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
