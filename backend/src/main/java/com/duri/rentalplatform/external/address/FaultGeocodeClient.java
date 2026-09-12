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
 * 좌표 변환 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 좌표를 돌려준다.
 *
 * <p>재시도 · 서킷 · 폴백을 Real 과 같은 인스턴스로 건다.
 */
@Component
@ConditionalOnProperty(prefix = "external.geocode", name = "mode", havingValue = "fault")
public class FaultGeocodeClient implements GeocodeClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockGeocodeClient delegate = new MockGeocodeClient();

    public FaultGeocodeClient(ExternalApiProperties properties) {
        this.settings = properties.geocode();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public Optional<Coordinates> geocode(String address) {
        FaultInjection.inject(settings);
        return delegate.geocode(address);
    }

    /**
     * 폴백. 좌표를 지어내지 않는다 — 임의 좌표를 넣으면 존재하지 않는 자리에 매물이 찍히고 반경
     * 검색 결과에 섞인다.
     */
    @SuppressWarnings("unused")
    private Optional<Coordinates> unavailable(String address, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
