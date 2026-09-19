package com.duri.rentalplatform.external.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.external.FaultInjection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FaultMailClient} 검증. 다른 Fault 테스트와 같이 애노테이션(서킷)이 아니라 클래스 자체가 던지는 것만 확인한다.
 */
class FaultMailClientTest {

    private static final String RECIPIENT = "user@example.com";
    private static final String LINK = "http://localhost:5173/password-reset/confirm?token=abc";

    @Test
    @DisplayName("error 모드는 즉시 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void errorModeThrows() {
        FaultMailClient client = new FaultMailClient(Duration.ofSeconds(5), FaultInjection.KIND_ERROR,
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.sendPasswordResetLink(RECIPIENT, LINK))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("delay 모드는 지연 뒤 정상으로 끝난다")
    void delayModeCompletes() {
        FaultMailClient client = new FaultMailClient(Duration.ofSeconds(5), FaultInjection.KIND_DELAY,
                Duration.ofMillis(50));

        assertThatCode(() -> client.sendPasswordResetLink(RECIPIENT, LINK)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("timeout 모드는 지연이 길어도 읽기 타임아웃 안에 실패한다")
    void timeoutModeFailsWithinReadTimeout() {
        FaultMailClient client = new FaultMailClient(Duration.ofMillis(150), FaultInjection.KIND_TIMEOUT,
                Duration.ofSeconds(5));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.sendPasswordResetLink(RECIPIENT, LINK)).isInstanceOf(BusinessException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    @DisplayName("폴백은 보낸 것처럼 끝내지 않고 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void fallbackThrows() throws Exception {
        FaultMailClient client = new FaultMailClient(Duration.ofSeconds(5), FaultInjection.KIND_ERROR,
                Duration.ofSeconds(2));
        Method fallback = FaultMailClient.class
                .getDeclaredMethod("unavailable", String.class, String.class, Throwable.class);
        fallback.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, RECIPIENT, LINK, new RuntimeException("서킷 오픈"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }
}
