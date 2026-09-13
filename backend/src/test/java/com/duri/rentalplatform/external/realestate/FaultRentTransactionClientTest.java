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

/**
 * {@link FaultRentTransactionClient} 검증.
 *
 * <p>{@code @Retry} · {@code @CircuitBreaker} 는 스프링 AOP 프록시가 있어야 동작한다. 이 클래스를
 * {@code new} 로 직접 만들어 호출하면 애노테이션은 걸리지 않으므로, 애노테이션이 아니라 <b>이 클래스
 * 자체가 무엇을 던지고 돌려주는지</b>만 확인한다. 서킷이 실제로 열리는지는 스프링 컨텍스트가 있어야
 * 확인되므로 이 테스트의 범위 밖이다.
 */
class FaultRentTransactionClientTest {

    private static final RentTransactionQuery QUERY =
            new RentTransactionQuery("11680", YearMonth.now().minusMonths(1), RentBuildingType.APARTMENT);

    @Test
    @DisplayName("error 모드는 위임 없이 즉시 BusinessException을 던진다")
    void errorModeThrowsImmediately() {
        FaultRentTransactionClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.findRentTransactions(QUERY))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 후 Mock과 같은 응답을 돌려준다")
    void delayModeReturnsTheSameResponseAsMock() {
        FaultRentTransactionClient client = clientOf(FaultInjection.KIND_DELAY, Duration.ofMillis(50));

        List<RentTransaction> actual = client.findRentTransactions(QUERY);
        List<RentTransaction> expected = new MockRentTransactionClient().findRentTransactions(QUERY);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("timeout 모드는 지연이 읽기 타임아웃보다 길어도 읽기 타임아웃 안에 실패한다")
    void timeoutModeFailsWithinReadTimeout() {
        FaultRentTransactionClient client =
                clientOf(FaultInjection.KIND_TIMEOUT, Duration.ofSeconds(5), Duration.ofMillis(150));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.findRentTransactions(QUERY)).isInstanceOf(BusinessException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    @DisplayName("폴백은 빈 목록이 아니라 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void fallbackThrowsBusinessExceptionInsteadOfAnEmptyList() throws Exception {
        FaultRentTransactionClient client = clientOf(FaultInjection.KIND_ERROR, Duration.ofSeconds(2));

        // 폴백은 서킷이 열렸을 때 스프링 프록시가 부르는 private 메서드라 정상 호출 경로가 없다.
        // 서킷이 실제로 여는지는 이 테스트의 범위 밖이지만, 폴백이 무엇을 던지는지는
        // 리플렉션으로 직접 확인한다.
        Method fallback = FaultRentTransactionClient.class
                .getDeclaredMethod("unavailable", RentTransactionQuery.class, Throwable.class);
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

    private FaultRentTransactionClient clientOf(String kind, Duration delay) {
        return clientOf(kind, delay, Duration.ofSeconds(5));
    }

    private FaultRentTransactionClient clientOf(String kind, Duration delay, Duration readTimeout) {
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, readTimeout, new ExternalApiProperties.FaultSettings(kind, delay));
        ExternalApiProperties properties = new ExternalApiProperties(settings, null, null, null);
        return new FaultRentTransactionClient(properties);
    }
}
