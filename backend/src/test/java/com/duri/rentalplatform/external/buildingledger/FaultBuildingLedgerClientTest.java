package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.external.FaultInjection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FaultBuildingLedgerClient} 검증.
 *
 * <p>{@code @Retry} · {@code @CircuitBreaker} 는 스프링 AOP 프록시가 있어야 동작한다. {@code new} 로 만든 인스턴스에는
 * 걸리지 않으므로 <b>이 클래스 자체가 무엇을 던지고 돌려주는지</b>만 확인한다. 서킷이 실제로 열리는지는 범위 밖이다.
 */
class FaultBuildingLedgerClientTest {

    private static final BuildingLedgerLookup LOOKUP = new BuildingLedgerLookup(1024L,
            new PropertyNaturalKey("서울특별시 시험구 시험로 1", new BigDecimal("42.50"), 3, 230_000_000L, 0L),
            "김임대", PropertyType.APARTMENT);

    @Test
    @DisplayName("error 모드는 위임 없이 즉시 EXTERNAL_API_UNAVAILABLE 을 던진다")
    void errorModeThrowsImmediately() {
        FaultBuildingLedgerClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.fetch(LOOKUP))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 후 Mock 과 같은 대장을 돌려준다")
    void delayModeReturnsTheSameDocumentAsMock() {
        FaultBuildingLedgerClient client = clientOf(FaultInjection.KIND_DELAY, Duration.ofMillis(50), Duration.ofSeconds(5));

        assertThat(client.fetch(LOOKUP)).isEqualTo(new MockBuildingLedgerClient().fetch(LOOKUP));
    }

    @Test
    @DisplayName("timeout 모드는 지연이 읽기 타임아웃보다 길어도 읽기 타임아웃 안에 실패한다")
    void timeoutModeFailsWithinReadTimeout() {
        FaultBuildingLedgerClient client =
                clientOf(FaultInjection.KIND_TIMEOUT, Duration.ofSeconds(5), Duration.ofMillis(150));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.fetch(LOOKUP)).isInstanceOf(BusinessException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    @DisplayName("폴백은 값을 채운 대장이 아니라 EXTERNAL_API_UNAVAILABLE 을 던진다")
    void fallbackThrowsInsteadOfFillingValues() throws Exception {
        FaultBuildingLedgerClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2), Duration.ofSeconds(5));

        // 폴백은 서킷이 열렸을 때 프록시가 부르는 private 메서드라 정상 호출 경로가 없다. 리플렉션으로 직접 부른다.
        Method fallback = FaultBuildingLedgerClient.class
                .getDeclaredMethod("unavailable", BuildingLedgerLookup.class, Throwable.class);
        fallback.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, LOOKUP, new RuntimeException("서킷 오픈"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    private FaultBuildingLedgerClient clientOf(String kind, Duration delay, Duration readTimeout) {
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, readTimeout, new ExternalApiProperties.FaultSettings(kind, delay));
        return new FaultBuildingLedgerClient(new ExternalApiProperties(null, null, null, settings));
    }
}
