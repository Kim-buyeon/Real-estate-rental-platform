package com.duri.rentalplatform.external.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FaultAddressNormalizeClient} 검증. {@link FaultRentTransactionClientTest} 와 같은 이유로
 * 애노테이션이 아니라 클래스 자체가 던지는 것 · 돌려주는 것만 확인한다.
 */
class FaultAddressNormalizeClientTest {

    private static final String RAW_ADDRESS = "서울특별시 강남구 역삼동 100-1";

    @Test
    @DisplayName("error 모드는 위임 없이 즉시 BusinessException을 던진다")
    void errorModeThrowsImmediately() {
        FaultAddressNormalizeClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.normalize(RAW_ADDRESS))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 후 Mock과 같은 결과를 돌려준다")
    void delayModeReturnsTheSameResultAsMock() {
        FaultAddressNormalizeClient client = clientOf(FaultInjection.KIND_DELAY, Duration.ofMillis(50));

        Optional<NormalizedAddress> actual = client.normalize(RAW_ADDRESS);
        Optional<NormalizedAddress> expected = new MockAddressNormalizeClient().normalize(RAW_ADDRESS);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("timeout 모드는 지연이 읽기 타임아웃보다 길어도 읽기 타임아웃 안에 실패한다")
    void timeoutModeFailsWithinReadTimeout() {
        FaultAddressNormalizeClient client =
                clientOf(FaultInjection.KIND_TIMEOUT, Duration.ofSeconds(5), Duration.ofMillis(150));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.normalize(RAW_ADDRESS)).isInstanceOf(BusinessException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    @DisplayName("폴백은 빈 값이 아니라 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void fallbackThrowsBusinessExceptionInsteadOfAnEmptyOptional() throws Exception {
        FaultAddressNormalizeClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        Method fallback = FaultAddressNormalizeClient.class
                .getDeclaredMethod("unavailable", String.class, Throwable.class);
        fallback.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, RAW_ADDRESS, new RuntimeException("서킷 오픈"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    private FaultAddressNormalizeClient clientOf(String kind, Duration delay) {
        return clientOf(kind, delay, Duration.ofSeconds(5));
    }

    private FaultAddressNormalizeClient clientOf(String kind, Duration delay, Duration readTimeout) {
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, readTimeout, new ExternalApiProperties.FaultSettings(kind, delay));
        ExternalApiProperties properties = new ExternalApiProperties(null, settings, null, null);
        return new FaultAddressNormalizeClient(properties);
    }
}
