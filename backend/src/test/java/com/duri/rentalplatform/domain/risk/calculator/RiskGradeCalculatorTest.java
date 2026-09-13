package com.duri.rentalplatform.domain.risk.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.vo.RiskGradeResult;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link RiskGradeCalculator} 판정 검증. 기대값 표는 이슈 #57 계획의 9행이고, 한 행이 한 케이스다.
 *
 * <p>CAUTION 경계는 계산기에 인자로 넘기는 픽스처 값이다. 판정 기준을 테스트가 정하지 않는다.
 */
class RiskGradeCalculatorTest {

    private static final long MARKET_PRICE = 300_000_000L;
    private static final BigDecimal CAUTION = new BigDecimal("70.00");

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void calculate(String caseName, boolean insuranceEligible, boolean negativeEquity, long riskAmount,
            BigDecimal cautionLeaseRatio, RiskGrade expectedGrade, GradeReason expectedReason) {
        RiskGradeResult result = RiskGradeCalculator.calculate(
                negativeEquity, insuranceEligible, riskAmount, MARKET_PRICE, cautionLeaseRatio);

        assertThat(result).isEqualTo(new RiskGradeResult(expectedGrade, expectedReason));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("RISK-01-01 70% 정확히 — 초과 아님",
                        true, false, 210_000_000L, CAUTION, RiskGrade.SAFE, GradeReason.INSURANCE_ELIGIBLE),
                Arguments.of("RISK-01-02 경계 +1원",
                        true, false, 210_000_001L, CAUTION, RiskGrade.CAUTION, GradeReason.LEASE_RATIO_CAUTION),
                Arguments.of("RISK-01-03 경계 −1원",
                        true, false, 209_999_999L, CAUTION, RiskGrade.SAFE, GradeReason.INSURANCE_ELIGIBLE),
                Arguments.of("RISK-01-04 깡통 선 80% 정확히 — 깡통 아님",
                        true, false, 240_000_000L, CAUTION, RiskGrade.CAUTION, GradeReason.LEASE_RATIO_CAUTION),
                Arguments.of("RISK-01-05 깡통전세는 가입 가능해도 DANGER",
                        true, true, 240_000_001L, CAUTION, RiskGrade.DANGER, GradeReason.NEGATIVE_EQUITY),
                Arguments.of("RISK-01-06 3사 불가",
                        false, false, 150_000_000L, CAUTION, RiskGrade.DANGER, GradeReason.INSURANCE_INELIGIBLE),
                Arguments.of("RISK-01-07 두 조건 동시 — 깡통전세 사유 우선",
                        false, true, 350_000_000L, CAUTION, RiskGrade.DANGER, GradeReason.NEGATIVE_EQUITY),
                Arguments.of("RISK-01-08 위험금액 0",
                        true, false, 0L, CAUTION, RiskGrade.SAFE, GradeReason.INSURANCE_ELIGIBLE),
                Arguments.of("RISK-01-09 경계 60.00 — 기준값을 바꾸면 CAUTION",
                        true, false, 195_000_000L, new BigDecimal("60.00"), RiskGrade.CAUTION,
                        GradeReason.LEASE_RATIO_CAUTION));
    }

    @Test
    @DisplayName("시세가 0 이하이면 판정하지 않는다")
    void rejectsNonPositiveMarketPrice() {
        assertThatThrownBy(() -> RiskGradeCalculator.calculate(false, true, 1L, 0L, CAUTION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
