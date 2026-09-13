package com.duri.rentalplatform.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FaultInjection} 검증.
 *
 * <p>서킷 · 재시도는 Resilience4j 애노테이션이 스프링 AOP 프록시로 붙이는 것이라 순수 단위
 * 테스트로는 확인할 수 없다. 여기서는 애노테이션이 아니라 <b>이 클래스 자체가 지연 · 오류 · 타임아웃
 * 세 모드에서 무엇을 던지고 얼마나 걸리는지</b>만 본다.
 */
class FaultInjectionTest {

    private static final Duration SHORT_DELAY = Duration.ofMillis(80);
    private static final Duration LONG_DELAY = Duration.ofMillis(2_000);
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(150);
    private static final Duration LONG_READ_TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("error 모드는 지연 없이 즉시 BusinessException(EXTERNAL_API_UNAVAILABLE)을 던진다")
    void errorKindFailsImmediatelyWithoutWaiting() {
        ExternalApiProperties.ClientSettings settings =
                settingsOf(FaultInjection.KIND_ERROR, LONG_DELAY, LONG_READ_TIMEOUT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> FaultInjection.inject(settings))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        long elapsedMillis = elapsedMillis(start);

        // 지연(2초)을 전혀 기다리지 않는다 — 그 절반에도 못 미쳐야 "즉시"다.
        assertThat(elapsedMillis).isLessThan(LONG_DELAY.toMillis() / 2);
    }

    @Test
    @DisplayName("delay 모드는 지정한 시간만큼 기다린 뒤 예외 없이 돌아온다")
    void delayKindSleepsThenReturnsNormally() {
        ExternalApiProperties.ClientSettings settings =
                settingsOf(FaultInjection.KIND_DELAY, SHORT_DELAY, LONG_READ_TIMEOUT);

        long start = System.nanoTime();
        FaultInjection.inject(settings);
        long elapsedMillis = elapsedMillis(start);

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(SHORT_DELAY.toMillis());
        assertThat(elapsedMillis).isLessThan(LONG_READ_TIMEOUT.toMillis());
    }

    @Test
    @DisplayName("timeout 모드는 지연이 읽기 타임아웃보다 길어도 읽기 타임아웃 안에 실패한다")
    void timeoutKindFailsAtReadTimeoutEvenWhenDelayIsLonger() {
        ExternalApiProperties.ClientSettings settings =
                settingsOf(FaultInjection.KIND_TIMEOUT, LONG_DELAY, SHORT_READ_TIMEOUT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> FaultInjection.inject(settings)).isInstanceOf(BusinessException.class);
        long elapsedMillis = elapsedMillis(start);

        // 지연(2초)이 아니라 읽기 타임아웃(150ms) 안에 실패해야 한다 — 여유를 둬도 지연의 절반에는
        // 못 미친다. 이것이 FaultInjection 이 KIND_TIMEOUT 에서 지연이 아니라 min(지연,타임아웃)
        // 만큼만 기다리는 이유다.
        assertThat(elapsedMillis).isLessThan(LONG_DELAY.toMillis() / 2);
    }

    @Test
    @DisplayName("timeout 모드에서 지연이 읽기 타임아웃보다 짧으면 지연만큼 기다린 뒤 실패한다")
    void timeoutKindUsesDelayWhenItIsShorterThanReadTimeout() {
        ExternalApiProperties.ClientSettings settings =
                settingsOf(FaultInjection.KIND_TIMEOUT, SHORT_DELAY, LONG_READ_TIMEOUT);

        long start = System.nanoTime();
        assertThatThrownBy(() -> FaultInjection.inject(settings)).isInstanceOf(BusinessException.class);
        long elapsedMillis = elapsedMillis(start);

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(SHORT_DELAY.toMillis());
        assertThat(elapsedMillis).isLessThan(LONG_READ_TIMEOUT.toMillis());
    }

    private ExternalApiProperties.ClientSettings settingsOf(String kind, Duration delay, Duration readTimeout) {
        return new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, readTimeout, new ExternalApiProperties.FaultSettings(kind, delay));
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
