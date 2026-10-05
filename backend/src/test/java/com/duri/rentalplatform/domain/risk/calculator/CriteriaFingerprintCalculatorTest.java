package com.duri.rentalplatform.domain.risk.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CriteriaFingerprintCalculator} — 저장된 판정 근거를 지금 기준으로도 쓸 수 있는지 가르는 지문. 기댓값 표가 없는 성질 검증이다:
 * 같은 입력은 같은 값이고, 판정이 읽는 값 하나라도 다르면 다른 값이다. 특정 해시 값은 단언하지 않는다 — 형식 판을 올리면 값이 바뀌는 것이
 * 의도라 해시 값을 박으면 정상 개정에도 깨진다.
 */
class CriteriaFingerprintCalculatorTest {

    private static final BigDecimal NEGATIVE_EQUITY = new BigDecimal("80.00");
    private static final BigDecimal CAUTION = new BigDecimal("70.00");

    @Test
    @DisplayName("같은 입력은 같은 지문이다 — 64자 16진")
    void sameInputSameFingerprint() {
        String first = fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock");
        String second = fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock");

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("기관 ID 맵의 삽입 순서는 지문에 영향이 없다")
    void idMapInsertionOrderDoesNotMatter() {
        Map<GuaranteeProvider, Long> reversed = new LinkedHashMap<>();
        reversed.put(GuaranteeProvider.SGI, 13L);
        reversed.put(GuaranteeProvider.HF, 12L);
        reversed.put(GuaranteeProvider.HUG, 11L);

        assertThat(fingerprint(baseCriteria(), reversed, NEGATIVE_EQUITY, CAUTION, "mock"))
                .isEqualTo(fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    @Test
    @DisplayName("소수의 자릿수 표기는 지문에 영향이 없다 — 90.0 과 90.00 은 같은 값")
    void decimalScaleDoesNotMatter() {
        String scaleTwo = fingerprint(baseCriteria(), baseIds(), new BigDecimal("80.00"), new BigDecimal("70.00"),
                "mock");
        String scaleZero = fingerprint(baseCriteria(), baseIds(), new BigDecimal("80"), new BigDecimal("70.0"),
                "mock");

        assertThat(scaleZero).isEqualTo(scaleTwo);
    }

    @Test
    @DisplayName("기관 순서는 응답 순서를 정하므로 순서가 다르면 지문도 다르다")
    void providerOrderMatters() {
        List<GuaranteeCriteriaSnapshot> reordered = List.of(
                baseCriteria().get(1), baseCriteria().get(0), baseCriteria().get(2));

        assertThat(fingerprint(reordered, baseIds(), NEGATIVE_EQUITY, CAUTION, "mock"))
                .isNotEqualTo(fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    @Test
    @DisplayName("깡통전세 선이 바뀌면 지문이 바뀐다")
    void negativeEquityRatioChangesFingerprint() {
        assertChanged(fingerprint(baseCriteria(), baseIds(), new BigDecimal("80.01"), CAUTION, "mock"));
    }

    @Test
    @DisplayName("SAFE/CAUTION 경계가 바뀌면 지문이 바뀐다")
    void cautionLeaseRatioChangesFingerprint() {
        assertChanged(fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, new BigDecimal("69.99"), "mock"));
    }

    @Test
    @DisplayName("기관 ID 가 바뀌면 지문이 바뀐다 — 분석 행의 가입 가능 기관 ID 가 달라진다")
    void guaranteeIdChangesFingerprint() {
        Map<GuaranteeProvider, Long> ids = baseIds();
        ids.put(GuaranteeProvider.HUG, 99L);

        assertChanged(fingerprint(baseCriteria(), ids, NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    @Test
    @DisplayName("대장 연동 모드가 바뀌면 지문이 바뀐다 — real 은 Mock 대장을 대장 없음으로 본다")
    void ledgerModeChangesFingerprint() {
        String mock = fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock");
        String real = fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "real");

        assertThat(real).isNotEqualTo(mock);
    }

    @Test
    @DisplayName("기관 기준의 값 하나만 바뀌어도 지문이 바뀐다")
    void eachGuaranteeFieldChangesFingerprint() {
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), new BigDecimal("89.00"),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                new BigDecimal("59.00"), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                null, c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit() + 1, c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), !c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), !c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                !c.rightViolationDisqualify(), c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), !c.loanLinkRequired(), c.productName(), c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), "다른상품", c.premiumRates()));
        assertHugChangedBy(c -> new GuaranteeCriteriaSnapshot(c.provider(), c.collateralRatio(),
                c.seniorDebtRatioLimit(), c.maxDeposit(), c.apartmentUnlimited(), c.violationDisqualify(),
                c.rightViolationDisqualify(), c.loanLinkRequired(), null, c.premiumRates()));
    }

    @Test
    @DisplayName("보증료율 구간의 값 하나만 바뀌어도 지문이 바뀐다")
    void eachPremiumBandFieldChangesFingerprint() {
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.OTHER, 0L, 100_000_000L,
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 1L, 100_000_000L,
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 0L, 100_000_001L,
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 0L, null,
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 0L, 100_000_000L,
                new BigDecimal("1"), new BigDecimal("80.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 0L, 100_000_000L,
                BigDecimal.ZERO, new BigDecimal("79.00"), new BigDecimal("0.115"))));
        assertChangedBy(c -> withBand(c, new PremiumRateBand(HouseType.APARTMENT, 0L, 100_000_000L,
                BigDecimal.ZERO, new BigDecimal("80.00"), new BigDecimal("0.116"))));
    }

    @Test
    @DisplayName("형식 판이 정규 문자열에 들어 있다 — 올리면 모든 지문이 바뀐다")
    void formatVersionIsPartOfCanonicalForm() {
        String canonical = CriteriaFingerprintCalculator.canonical(
                baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock");

        assertThat(canonical).startsWith("format=" + CriteriaFingerprintCalculator.FORMAT_VERSION + "\n");
    }

    private static void assertChanged(String changed) {
        assertThat(changed).isNotEqualTo(
                fingerprint(baseCriteria(), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    private static void assertChangedBy(UnaryOperator<List<GuaranteeCriteriaSnapshot>> change) {
        assertChanged(fingerprint(change.apply(baseCriteria()), baseIds(), NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    /** 첫 기관(HUG)의 기준만 바꾼다. */
    private static void assertHugChangedBy(UnaryOperator<GuaranteeCriteriaSnapshot> change) {
        List<GuaranteeCriteriaSnapshot> base = baseCriteria();
        assertChanged(fingerprint(List.of(change.apply(base.get(0)), base.get(1), base.get(2)), baseIds(),
                NEGATIVE_EQUITY, CAUTION, "mock"));
    }

    private static List<GuaranteeCriteriaSnapshot> withBand(List<GuaranteeCriteriaSnapshot> base,
            PremiumRateBand band) {
        GuaranteeCriteriaSnapshot hug = base.get(0);
        return List.of(new GuaranteeCriteriaSnapshot(hug.provider(), hug.collateralRatio(),
                hug.seniorDebtRatioLimit(), hug.maxDeposit(), hug.apartmentUnlimited(), hug.violationDisqualify(),
                hug.rightViolationDisqualify(), hug.loanLinkRequired(), hug.productName(), List.of(band)),
                base.get(1), base.get(2));
    }

    private static String fingerprint(List<GuaranteeCriteriaSnapshot> criteria,
            Map<GuaranteeProvider, Long> ids, BigDecimal negativeEquity, BigDecimal caution, String mode) {
        return CriteriaFingerprintCalculator.calculate(criteria, ids, negativeEquity, caution, mode);
    }

    private static Map<GuaranteeProvider, Long> baseIds() {
        Map<GuaranteeProvider, Long> ids = new LinkedHashMap<>();
        ids.put(GuaranteeProvider.HUG, 11L);
        ids.put(GuaranteeProvider.HF, 12L);
        ids.put(GuaranteeProvider.SGI, 13L);
        return ids;
    }

    /** HUG(요율 구간 하나) → HF → SGI. */
    private static List<GuaranteeCriteriaSnapshot> baseCriteria() {
        return List.of(
                new GuaranteeCriteriaSnapshot(GuaranteeProvider.HUG, new BigDecimal("90.00"),
                        new BigDecimal("60.00"), 700_000_000L, false, true, true, false, "전세보증금반환보증",
                        List.of(new PremiumRateBand(HouseType.APARTMENT, 0L, 100_000_000L, BigDecimal.ZERO,
                                new BigDecimal("80.00"), new BigDecimal("0.115")))),
                new GuaranteeCriteriaSnapshot(GuaranteeProvider.HF, new BigDecimal("90.00"),
                        null, 700_000_000L, false, true, true, true, null, List.of()),
                new GuaranteeCriteriaSnapshot(GuaranteeProvider.SGI, new BigDecimal("90.00"),
                        null, 1_000_000_000L, true, true, true, false, null, List.of()));
    }
}
