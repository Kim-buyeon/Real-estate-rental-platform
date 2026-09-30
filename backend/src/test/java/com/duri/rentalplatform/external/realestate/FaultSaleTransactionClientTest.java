package com.duri.rentalplatform.external.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link FaultSaleTransactionClient} 검증. 범위는 {@code FaultRentTransactionClientTest} 와 같다 — 애노테이션(재시도 · 서킷)이
 * 아니라 이 클래스 자체가 무엇을 던지고 돌려주는지만 본다.
 */
class FaultSaleTransactionClientTest {

    private static final SaleTransactionQuery QUERY =
            new SaleTransactionQuery("11680", YearMonth.now().minusMonths(1), SaleBuildingType.APARTMENT);

    @Test
    @DisplayName("error 모드는 위임 없이 즉시 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void errorModeThrowsImmediately() {
        FaultSaleTransactionClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.findSaleTransactions(QUERY))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 후 Mock과 같은 응답을 돌려준다")
    void delayModeReturnsTheSameResponseAsMock() {
        FaultSaleTransactionClient client = clientOf(FaultInjection.KIND_DELAY, Duration.ofMillis(50));

        List<SaleTransaction> actual = client.findSaleTransactions(QUERY);
        List<SaleTransaction> expected = new MockSaleTransactionClient().findSaleTransactions(QUERY);

        assertThat(actual).isNotEmpty().isEqualTo(expected);
    }

    @Test
    @DisplayName("폴백은 빈 목록이 아니라 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다 — 표본을 지어내지 않는다")
    void fallbackThrowsBusinessExceptionInsteadOfAnEmptyList() throws Exception {
        FaultSaleTransactionClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        // 폴백은 서킷이 열렸을 때 스프링 프록시가 부르는 private 메서드라 리플렉션으로 직접 부른다.
        Method fallback = FaultSaleTransactionClient.class
                .getDeclaredMethod("unavailable", SaleTransactionQuery.class, Throwable.class);
        fallback.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, QUERY, new RuntimeException("서킷 오픈"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("Real 의 폴백도 빈 목록이 아니라 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void realFallbackThrowsBusinessException() throws Exception {
        Method fallback = RealSaleTransactionClient.class
                .getDeclaredMethod("unavailable", SaleTransactionQuery.class, Throwable.class);
        fallback.setAccessible(true);
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "real", "https://example.invalid", "encodedKey", null, null, null);
        RealSaleTransactionClient real = new RealSaleTransactionClient(
                RestClient.create(),
                new ExternalApiProperties(null, null, null, null, settings));

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(real, QUERY, new RuntimeException("재시도 소진"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    private FaultSaleTransactionClient clientOf(String kind, Duration delay) {
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, Duration.ofSeconds(5), new ExternalApiProperties.FaultSettings(kind, delay));
        return new FaultSaleTransactionClient(new ExternalApiProperties(null, null, null, null, settings));
    }
}
