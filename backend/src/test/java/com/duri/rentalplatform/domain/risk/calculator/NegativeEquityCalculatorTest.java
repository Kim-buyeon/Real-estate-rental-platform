package com.duri.rentalplatform.domain.risk.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.domain.risk.vo.MortgageEntry;
import com.duri.rentalplatform.domain.risk.vo.NegativeEquityResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link NegativeEquityCalculator} 판정 검증. 기대값 표는 이슈 #51 계획의 12행이고, 한 행이 한 케이스다.
 *
 * <p>기준 비율은 계산기에 인자로 넘기는 픽스처 값이다. 판정 기준을 테스트가 정하지 않는다.
 */
class NegativeEquityCalculatorTest {

    private static final long MARKET_PRICE = 300_000_000L;
    private static final BigDecimal RATIO = new BigDecimal("80.00");

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void calculate(String caseName, List<MortgageEntry> entries, long deposit, BigDecimal ratio,
            long expectedSeniorDebtTotal, String expectedDebtRatio, boolean expectedNegativeEquity) {
        NegativeEquityResult result = NegativeEquityCalculator.calculate(entries, deposit, MARKET_PRICE, ratio);

        assertThat(result.seniorDebtTotal()).isEqualTo(expectedSeniorDebtTotal);
        assertThat(result.debtRatio()).isEqualTo(new BigDecimal(expectedDebtRatio));
        assertThat(result.negativeEquity()).isEqualTo(expectedNegativeEquity);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("RISK-02-01 을구 없음, 보증금 = 기준금액 — 초과 아님",
                        List.of(), 240_000_000L, RATIO, 0L, "80.00", false),
                Arguments.of("RISK-02-02 을구 없음, 기준금액 +1원 — 반올림 80.00 이어도 깡통",
                        List.of(), 240_000_001L, RATIO, 0L, "80.00", true),
                Arguments.of("RISK-02-03 을구 없음, 기준금액 −1원 — 아님",
                        List.of(), 239_999_999L, RATIO, 0L, "80.00", false),
                Arguments.of("RISK-02-04 채권최고액 유효 — 합산",
                        List.of(active(250_000_000L, 0L)), 50_000_000L, RATIO, 250_000_000L, "100.00", true),
                Arguments.of("RISK-02-05 채권최고액 말소 — 제외",
                        List.of(cancelled(250_000_000L, 0L)), 100_000_000L, RATIO, 0L, "33.33", false),
                Arguments.of("RISK-02-06 선순위 임차보증금 유효 — 합산, 경계",
                        List.of(active(0L, 60_000_000L)), 180_000_000L, RATIO, 60_000_000L, "80.00", false),
                Arguments.of("RISK-02-07 채권최고액 유효 2건 — 다건 합산, 경계 +1원",
                        List.of(active(50_000_000L, 0L), active(70_000_000L, 0L)), 120_000_001L, RATIO,
                        120_000_000L, "80.00", true),
                Arguments.of("RISK-02-08 명세 예시 — 116.67",
                        List.of(active(250_000_000L, 0L)), 100_000_000L, RATIO, 250_000_000L, "116.67", true),
                Arguments.of("RISK-02-09 을구 없음 — HALF_UP 66.67",
                        List.of(), 200_000_000L, RATIO, 0L, "66.67", false),
                Arguments.of("RISK-02-10 기준 비율 70.00 — 기준값을 바꾸면 깡통",
                        List.of(), 240_000_000L, new BigDecimal("70.00"), 0L, "80.00", true),
                Arguments.of("RISK-02-11 선순위 임차보증금 NULL — 0 으로 합산",
                        List.of(active(0L, null)), 10_000_000L, RATIO, 0L, "3.33", false)
        );
    }

    @Test
    @DisplayName("RISK-02-12 시세 0 — 판정 불가")
    void marketPriceZero() {
        assertThatThrownBy(() -> NegativeEquityCalculator.calculate(List.of(), 10_000_000L, 0L, RATIO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MortgageEntry active(long maxBondAmount, Long priorTenantDeposit) {
        return new MortgageEntry(maxBondAmount, priorTenantDeposit, true);
    }

    private static MortgageEntry cancelled(long maxBondAmount, Long priorTenantDeposit) {
        return new MortgageEntry(maxBondAmount, priorTenantDeposit, false);
    }
}
