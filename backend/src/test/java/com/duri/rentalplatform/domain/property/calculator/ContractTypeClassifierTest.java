package com.duri.rentalplatform.domain.property.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ContractTypeClassifier} 판정 검증.
 *
 * <p>기대값 표 — 클래스 Javadoc이 근거다. 월세가 0 이하면 전세, 그 외에는 보증금이 월세의
 * {@code SEMI_DEPOSIT_MONTHS}(240)개월치를 <b>넘을 때만</b> 반전세이고 나머지는 월세다. 경계는
 * "넘으면"(초과)이므로 정확히 240개월치는 아직 반전세가 아니다 — 그 경계값을 직접 넣어 확인한다.
 *
 * <table border="1">
 *   <caption>기대값 표</caption>
 *   <tr><th>보증금</th><th>월세</th><th>기대 결과</th><th>비고</th></tr>
 *   <tr><td>3억</td><td>0</td><td>DEPOSIT_ONLY</td><td>월세 0 — 전세</td></tr>
 *   <tr><td>0</td><td>0</td><td>DEPOSIT_ONLY</td><td>월세 0의 경계값(0)</td></tr>
 *   <tr><td>50,000,000</td><td>500,000</td><td>MONTHLY_RENT</td><td>50,000,000 = 500,000 × 100(&lt;240)</td></tr>
 *   <tr><td>120,000,000</td><td>500,000</td><td>MONTHLY_RENT</td><td>정확히 240개월치 — 초과 아님</td></tr>
 *   <tr><td>120,000,001</td><td>500,000</td><td>SEMI_DEPOSIT</td><td>240개월치보다 1원 많음 — 초과</td></tr>
 * </table>
 */
class ContractTypeClassifierTest {

    @Test
    @DisplayName("월세가 0이면 전세다")
    void classifiesAsDepositOnlyWhenMonthlyRentIsZero() {
        assertThat(ContractTypeClassifier.classify(300_000_000L, 0L))
                .isEqualTo(ContractType.DEPOSIT_ONLY);
    }

    @ParameterizedTest(name = "월세 {1}(음수 포함)이면 전세로 취급한다")
    @CsvSource({
            "300000000, -1",
            "0, 0"
    })
    @DisplayName("월세가 0 이하(음수 포함)이면 전세로 취급한다")
    void classifiesAsDepositOnlyWhenMonthlyRentIsNotPositive(long deposit, long monthlyRent) {
        assertThat(ContractTypeClassifier.classify(deposit, monthlyRent))
                .isEqualTo(ContractType.DEPOSIT_ONLY);
    }

    @Test
    @DisplayName("보증금이 월세의 240개월치를 넘지 않으면 월세다")
    void classifiesAsMonthlyRentWhenBelowThreshold() {
        assertThat(ContractTypeClassifier.classify(50_000_000L, 500_000L))
                .isEqualTo(ContractType.MONTHLY_RENT);
    }

    @Test
    @DisplayName("보증금이 정확히 월세의 240개월치면 아직 반전세가 아니라 월세다 — 경계는 초과부터다")
    void classifiesAsMonthlyRentAtExactlyTheBoundary() {
        long monthlyRent = 500_000L;
        long exactlyTwoHundredFortyMonths = monthlyRent * 240L;

        assertThat(ContractTypeClassifier.classify(exactlyTwoHundredFortyMonths, monthlyRent))
                .isEqualTo(ContractType.MONTHLY_RENT);
    }

    @Test
    @DisplayName("보증금이 월세의 240개월치를 1원이라도 넘으면 반전세다")
    void classifiesAsSemiDepositJustAboveTheBoundary() {
        long monthlyRent = 500_000L;
        long justAboveTwoHundredFortyMonths = monthlyRent * 240L + 1L;

        assertThat(ContractTypeClassifier.classify(justAboveTwoHundredFortyMonths, monthlyRent))
                .isEqualTo(ContractType.SEMI_DEPOSIT);
    }
}
