package com.duri.rentalplatform.external.loanrate;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FaultJeonseLoanRateClient} — 이 클래스가 무엇을 던지고 돌려주는지만 본다. 서킷이 실제로 열리는지는 스프링 프록시가 있어야
 * 확인되므로 범위 밖이다({@code FaultRentTransactionClientTest} 와 같다).
 */
class FaultJeonseLoanRateClientTest {

    private static final LoanRateQuery QUERY = new LoanRateQuery(YearMonth.of(2026, 8), LoanRateHouseType.APARTMENT);

    @Test
    @DisplayName("error 모드는 즉시 EXTERNAL_API_UNAVAILABLE")
    void errorModeThrows() {
        assertThatThrownBy(() -> clientOf(FaultInjection.KIND_ERROR).findBankLoanRates(QUERY))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 뒤 Mock 과 같은 응답")
    void delayModeReturnsMockResponse() {
        assertThat(clientOf(FaultInjection.KIND_DELAY).findBankLoanRates(QUERY))
                .isEqualTo(new MockJeonseLoanRateClient().findBankLoanRates(QUERY));
    }

    @Test
    @DisplayName("폴백은 빈 목록이 아니라 EXTERNAL_API_UNAVAILABLE 을 던진다")
    void fallbackThrows() throws Exception {
        Method fallback = FaultJeonseLoanRateClient.class
                .getDeclaredMethod("unavailable", LoanRateQuery.class, Throwable.class);
        fallback.setAccessible(true);
        FaultJeonseLoanRateClient client = clientOf(FaultInjection.KIND_ERROR);

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

    private FaultJeonseLoanRateClient clientOf(String kind) {
        ExternalApiProperties.ClientSettings settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, Duration.ofSeconds(5),
                new ExternalApiProperties.FaultSettings(kind, Duration.ofMillis(50)));
        return new FaultJeonseLoanRateClient(new ExternalApiProperties(null, null, null, null, null, settings));
    }
}
