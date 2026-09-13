package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 건축물대장 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 Mock 과 같은 대장을 돌려준다.
 *
 * <p>정상 응답을 Mock 에 위임하는 이유 — Fault 가 자체 데이터를 들고 있으면 「지연은 있었지만 값은 정상」인 경로를
 * Mock 경로와 다른 데이터로 검증하게 된다.
 *
 * <p><b>Real 이 붙었을 때와 같은 격리를 건다.</b> 인스턴스 이름은 인터페이스 상수이고, 폴백은 예외를 던진다.
 * 이 구현으로 「연속 실패 뒤 서킷이 열리는가」 · 「열린 동안 저장 없이 503 이 나가는가」를 확인한다.
 */
@Component
@ConditionalOnProperty(prefix = "external.building-ledger", name = "mode", havingValue = "fault")
public class FaultBuildingLedgerClient implements BuildingLedgerClient {

    private final ExternalApiProperties.ClientSettings settings;
    private final MockBuildingLedgerClient delegate = new MockBuildingLedgerClient();

    public FaultBuildingLedgerClient(ExternalApiProperties properties) {
        this.settings = properties.buildingLedger();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public BuildingLedgerDocument fetch(BuildingLedgerLookup lookup) {
        FaultInjection.inject(settings);
        return delegate.fetch(lookup);
    }

    /**
     * 폴백. 값을 채운 대장을 돌려주지 않는다 — 위반건축물 「아님」으로 채우면 외부 장애가 안전 판정의 근거로
     * 저장된다.
     */
    @SuppressWarnings("unused")
    private BuildingLedgerDocument unavailable(BuildingLedgerLookup lookup, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
