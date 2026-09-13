package com.duri.rentalplatform.domain.loan.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitInput;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JeonseLoanLimitCalculator} — 이슈 #63 기대값 표 LOAN-01-01 ~ 10. 07(주택 보유 · 소득 0 → 422)은 서비스가 거르므로
 * 여기서는 계산기의 방어만 보고, 응답 코드는 서비스 테스트가 본다.
 *
 * <p>기준값은 기대값 표의 기준 행을 픽스처로 둔다. 계산기 안의 상수가 아니라 인자로 넘기는 입력이다.
 */
class JeonseLoanLimitCalculatorTest {

    private static final long NO_HOUSE_CAP = 400_000_000L;
    private static final long ONE_HOUSE_CAP = 180_000_000L;
    private static final long PRODUCT_LIMIT = 400_000_000L;
    private static final LoanLimitCriteria BASE = criteria("80.00", PRODUCT_LIMIT);

    private static LoanLimitCriteria criteria(String depositRatio, long productLimit) {
        return new LoanLimitCriteria(new BigDecimal(depositRatio), NO_HOUSE_CAP, ONE_HOUSE_CAP,
                new BigDecimal("40.00"), new BigDecimal("3.00"), new BigDecimal("4.200"), productLimit);
    }

    @Test
    @DisplayName("01 무주택 · 보증금 3억 → 보증금 기준 2.4억, DSR 미적용, DTI 20.2")
    void noHouseDepositRatio() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 50_000_000L, 0L, 300_000_000L), BASE);

        assertThat(result).isEqualTo(new LoanLimitResult(240_000_000L, 400_000_000L, null, null, 240_000_000L,
                AppliedRegulation.DEPOSIT_RATIO, new BigDecimal("20.2")));
    }

    @Test
    @DisplayName("02 무주택 · 보증금 6억 → 보증기관 상한 4억")
    void noHouseGuaranteeCap() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 50_000_000L, 0L, 600_000_000L), BASE);

        assertThat(result.depositLimit()).isEqualTo(480_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(400_000_000L);
        assertThat(result.dsrLimit()).isNull();
        assertThat(result.stressDsrLimit()).isNull();
        assertThat(result.finalLimit()).isEqualTo(400_000_000L);
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.GUARANTEE_CAP);
    }

    @Test
    @DisplayName("03 보증금 기준과 상한이 같으면 앞 순서(DEPOSIT_RATIO)")
    void tieKeepsEarlierItem() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 50_000_000L, 0L, 500_000_000L), BASE);

        assertThat(result.depositLimit()).isEqualTo(400_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(400_000_000L);
        assertThat(result.dsrLimit()).isNull();
        assertThat(result.finalLimit()).isEqualTo(400_000_000L);
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.DEPOSIT_RATIO);
    }

    @Test
    @DisplayName("04 주택 보유 → 상한 1.8억, DSR · 스트레스 DSR 병기")
    void oneHouseGuaranteeCap() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(true, 50_000_000L, 0L, 300_000_000L), BASE);

        assertThat(result.depositLimit()).isEqualTo(240_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(180_000_000L);
        assertThat(result.dsrLimit()).isEqualTo(476_190_476L);
        assertThat(result.stressDsrLimit()).isEqualTo(277_777_777L);
        assertThat(result.finalLimit()).isEqualTo(180_000_000L);
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.GUARANTEE_CAP);
    }

    @Test
    @DisplayName("05 주택 보유 · 기존 상환 800만 → DSR 결정, 원 단위 버림, DTI 40.0")
    void oneHouseDsrTruncated() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(true, 30_000_000L, 8_000_000L, 200_000_000L), BASE);

        assertThat(result).isEqualTo(new LoanLimitResult(160_000_000L, 180_000_000L, 95_238_095L, 55_555_555L,
                95_238_095L, AppliedRegulation.DSR, new BigDecimal("40.0")));
    }

    @Test
    @DisplayName("06 여력이 음수면 0 → DSR 한도 0")
    void negativeCapacityBecomesZero() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(true, 20_000_000L, 9_000_000L, 200_000_000L), BASE);

        assertThat(result.depositLimit()).isEqualTo(160_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(180_000_000L);
        assertThat(result.dsrLimit()).isZero();
        assertThat(result.stressDsrLimit()).isZero();
        assertThat(result.finalLimit()).isZero();
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.DSR);
    }

    @Test
    @DisplayName("07 주택 보유 · 소득 0 은 계산기가 받지 않는다(서비스가 422 로 먼저 거른다)")
    void oneHouseWithoutIncomeRejected() {
        assertThatThrownBy(() -> JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(true, 0L, 0L, 200_000_000L), BASE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("08 무주택 · 소득 0 → 계산한다, DTI 참고는 null")
    void noHouseWithoutIncome() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 0L, 0L, 200_000_000L), BASE);

        assertThat(result).isEqualTo(new LoanLimitResult(160_000_000L, 400_000_000L, null, null, 160_000_000L,
                AppliedRegulation.DEPOSIT_RATIO, null));
    }

    @Test
    @DisplayName("09 보증금 비율 기준값을 70 으로 바꾸면 2.1억")
    void depositRatioFromCriteria() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 50_000_000L, 0L, 300_000_000L), criteria("70.00", PRODUCT_LIMIT));

        assertThat(result.depositLimit()).isEqualTo(210_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(400_000_000L);
        assertThat(result.dsrLimit()).isNull();
        assertThat(result.finalLimit()).isEqualTo(210_000_000L);
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.DEPOSIT_RATIO);
    }

    @Test
    @DisplayName("10 상품 한도 3.5억이 가장 낮으면 PRODUCT_LIMIT")
    void productLimit() {
        LoanLimitResult result = JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(false, 50_000_000L, 0L, 600_000_000L), criteria("80.00", 350_000_000L));

        assertThat(result.depositLimit()).isEqualTo(480_000_000L);
        assertThat(result.guaranteeCapLimit()).isEqualTo(400_000_000L);
        assertThat(result.dsrLimit()).isNull();
        assertThat(result.finalLimit()).isEqualTo(350_000_000L);
        assertThat(result.appliedRegulation()).isEqualTo(AppliedRegulation.PRODUCT_LIMIT);
    }
}
