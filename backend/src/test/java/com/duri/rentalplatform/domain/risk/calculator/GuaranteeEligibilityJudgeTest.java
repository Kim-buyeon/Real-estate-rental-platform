package com.duri.rentalplatform.domain.risk.calculator;

import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.ADDRESS_MISMATCH;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.DEBT_RATIO_EXCEEDED;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.DEPOSIT_LIMIT_EXCEEDED;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.OWNER_MISMATCH;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.RIGHT_VIOLATION;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.SENIOR_DEBT_RATIO_EXCEEDED;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition.VIOLATION_BUILDING;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider.HF;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider.HUG;
import static com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider.SGI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeInput;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeJudgementResult;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
import com.duri.rentalplatform.domain.risk.vo.ProviderJudgement;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link GuaranteeEligibilityJudge} 판정 검증. 기대값 표는 이슈 #55 계획의 17행이고, 한 행이 한 케이스다.
 *
 * <p>기준은 V7 시드와 같은 값으로 테스트가 만든 픽스처다(DB 없음). 판정 기준을 판정기가 갖지 않으므로 기준값을 바꾸면
 * 결과가 바뀐다(RISK-05-15). 표가 「—」로 둔 칸도 기관 결과 전체를 대조하므로 산출값을 채워 둔다.
 */
class GuaranteeEligibilityJudgeTest {

    private static final long MARKET = 300_000_000L;
    private static final long DEPOSIT = 200_000_000L;

    private static final String HUG_PRODUCT = "전세보증금반환보증";
    private static final String HF_PRODUCT = "전세지킴보증";
    private static final String SGI_PRODUCT = "전세금보장신용보험";

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void judge(String caseName, GuaranteeInput input, List<GuaranteeCriteriaSnapshot> criteria,
            List<ProviderJudgement> expectedProviders) {
        GuaranteeJudgementResult result = GuaranteeEligibilityJudge.judge(input, criteria);

        assertThat(result.providers()).containsExactlyElementsOf(expectedProviders);
        assertThat(result.insuranceEligible())
                .isEqualTo(expectedProviders.stream().anyMatch(ProviderJudgement::eligible));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("RISK-05-01 모두 통과 — 전세가율 66.67",
                        input(MARKET, DEPOSIT, 0L, HouseType.APARTMENT), seed(),
                        List.of(ok(HUG, 270_000_000L, null), ok(HF, 270_000_000L, 80_000L),
                                ok(SGI, 270_000_000L, 458_000L))),
                Arguments.of("RISK-05-02 전세가율 90.00 경계 — 통과, 80~90 · 60~90 요율",
                        input(MARKET, 270_000_000L, 0L, HouseType.APARTMENT), seed(),
                        List.of(ok(HUG, 270_000_000L, null), ok(HF, 270_000_000L, 486_000L),
                                ok(SGI, 270_000_000L, 618_300L))),
                Arguments.of("RISK-05-03 전세가율 경계 +1원 — 3사 초과",
                        input(MARKET, 270_000_001L, 0L, HouseType.APARTMENT), seed(),
                        List.of(fail(HUG, 270_000_000L, DEBT_RATIO_EXCEEDED),
                                fail(HF, 270_000_000L, DEBT_RATIO_EXCEEDED),
                                fail(SGI, 270_000_000L, DEBT_RATIO_EXCEEDED))),
                Arguments.of("RISK-05-04 선순위 60% 경계 — 통과",
                        input(MARKET, 50_000_000L, 180_000_000L, HouseType.APARTMENT), seed(),
                        List.of(ok(HUG, 90_000_000L, null), ok(HF, 90_000_000L, 55_000L),
                                ok(SGI, 90_000_000L, 114_500L))),
                Arguments.of("RISK-05-05 선순위 60% +1원 — HUG 만, HF · SGI 한도 NULL",
                        input(MARKET, 50_000_000L, 180_000_001L, HouseType.APARTMENT), seed(),
                        List.of(fail(HUG, 89_999_999L, SENIOR_DEBT_RATIO_EXCEEDED),
                                ok(HF, 89_999_999L, 55_000L), ok(SGI, 89_999_999L, 114_500L))),
                Arguments.of("RISK-05-06 보증금 7억 +1원 — HUG · HF 초과, SGI 아파트 무제한",
                        input(1_000_000_000L, 700_000_001L, 0L, HouseType.APARTMENT), seed(),
                        List.of(fail(HUG, 900_000_000L, DEPOSIT_LIMIT_EXCEEDED),
                                fail(HF, 900_000_000L, DEPOSIT_LIMIT_EXCEEDED),
                                ok(SGI, 900_000_000L, 1_603_000L))),
                Arguments.of("RISK-05-07 보증금 10억 +1원 비아파트 — SGI 무제한은 아파트만",
                        input(2_000_000_000L, 1_000_000_001L, 0L, HouseType.OTHER), seed(),
                        List.of(fail(HUG, 1_800_000_000L, DEPOSIT_LIMIT_EXCEEDED),
                                fail(HF, 1_800_000_000L, DEPOSIT_LIMIT_EXCEEDED),
                                fail(SGI, 1_800_000_000L, DEPOSIT_LIMIT_EXCEEDED))),
                Arguments.of("RISK-05-08 보증금 7억 경계 — 통과",
                        input(1_000_000_000L, 700_000_000L, 0L, HouseType.APARTMENT), seed(),
                        List.of(ok(HUG, 900_000_000L, null), ok(HF, 900_000_000L, 280_000L),
                                ok(SGI, 900_000_000L, 1_603_000L))),
                Arguments.of("RISK-05-09 위반건축물 — 3사 불가",
                        new GuaranteeInput(MARKET, DEPOSIT, 0L, HouseType.APARTMENT, true, List.of(), true, true),
                        seed(),
                        List.of(fail(HUG, 270_000_000L, VIOLATION_BUILDING),
                                fail(HF, 270_000_000L, VIOLATION_BUILDING),
                                fail(SGI, 270_000_000L, VIOLATION_BUILDING))),
                Arguments.of("RISK-05-10 권리 침해 압류 — 3사 불가",
                        new GuaranteeInput(MARKET, DEPOSIT, 0L, HouseType.APARTMENT, false,
                                List.of(OwnershipRightType.SEIZURE), true, true),
                        seed(),
                        List.of(fail(HUG, 270_000_000L, RIGHT_VIOLATION),
                                fail(HF, 270_000_000L, RIGHT_VIOLATION),
                                fail(SGI, 270_000_000L, RIGHT_VIOLATION))),
                Arguments.of("RISK-05-11 명의 · 주소 불일치 — 둘 다 담는다",
                        new GuaranteeInput(MARKET, DEPOSIT, 0L, HouseType.APARTMENT, false, List.of(), false, false),
                        seed(),
                        List.of(fail(HUG, 270_000_000L, OWNER_MISMATCH, ADDRESS_MISMATCH),
                                fail(HF, 270_000_000L, OWNER_MISMATCH, ADDRESS_MISMATCH),
                                fail(SGI, 270_000_000L, OWNER_MISMATCH, ADDRESS_MISMATCH))),
                Arguments.of("RISK-05-12 명세 예시 — 위배 전부 수집",
                        input(MARKET, 100_000_000L, 250_000_000L, HouseType.APARTMENT), seed(),
                        List.of(fail(HUG, 20_000_000L, DEBT_RATIO_EXCEEDED, SENIOR_DEBT_RATIO_EXCEEDED),
                                fail(HF, 20_000_000L, DEBT_RATIO_EXCEEDED),
                                fail(SGI, 20_000_000L, DEBT_RATIO_EXCEEDED))),
                Arguments.of("RISK-05-13 보증한도 음수 — 0",
                        input(MARKET, 100_000_000L, 300_000_000L, HouseType.APARTMENT), seed(),
                        List.of(fail(HUG, 0L, DEBT_RATIO_EXCEEDED, SENIOR_DEBT_RATIO_EXCEEDED),
                                fail(HF, 0L, DEBT_RATIO_EXCEEDED),
                                fail(SGI, 0L, DEBT_RATIO_EXCEEDED))),
                Arguments.of("RISK-05-14 비아파트 — 유형별 요율",
                        input(MARKET, DEPOSIT, 0L, HouseType.OTHER), seed(),
                        List.of(ok(HUG, 270_000_000L, null), ok(HF, 270_000_000L, 80_000L),
                                ok(SGI, 270_000_000L, 520_000L))),
                Arguments.of("RISK-05-15 HUG 담보인정비율 60.00 — 기준값을 바꾸면 불가",
                        input(MARKET, DEPOSIT, 0L, HouseType.APARTMENT),
                        List.of(hug(new BigDecimal("60.00")), hf(), sgi()),
                        List.of(fail(HUG, 180_000_000L, DEBT_RATIO_EXCEEDED), ok(HF, 270_000_000L, 80_000L),
                                ok(SGI, 270_000_000L, 458_000L))),
                Arguments.of("RISK-05-16 전세가율 50.00 — 요율 구간 0~50 에 든다",
                        input(MARKET, 150_000_000L, 0L, HouseType.APARTMENT), seed(),
                        List.of(ok(HUG, 270_000_000L, null), ok(HF, 270_000_000L, 60_000L),
                                ok(SGI, 270_000_000L, 240_000L)))
        );
    }

    @Test
    @DisplayName("RISK-05-17 개인 확인 사항 고정 목록, HF 대출 연계는 기관 결과에 표기")
    void personalConditionsAndLoanLink() {
        GuaranteeJudgementResult result = GuaranteeEligibilityJudge.judge(
                input(MARKET, DEPOSIT, 0L, HouseType.APARTMENT), seed());

        assertThat(result.personalConditions()).containsExactly(PersonalCondition.ANNUAL_INCOME,
                PersonalCondition.APPLICATION_DEADLINE, PersonalCondition.NEW_OR_RENEWAL,
                PersonalCondition.RESIDENTIAL_USE_NOTATION, PersonalCondition.BROKER_CONTRACT,
                PersonalCondition.MOVE_IN_AND_FIXED_DATE);
        assertThat(result.providers())
                .extracting(ProviderJudgement::provider, ProviderJudgement::eligible,
                        ProviderJudgement::loanLinkRequired)
                .containsExactly(
                        tuple(HUG, true, false),
                        tuple(HF, true, true),
                        tuple(SGI, true, false));
    }

    // ── 입력 ──────────────────────────────────────────────────────────────

    /** 위반 없음 · 권리 침해 없음 · 명의 · 주소 일치. */
    private static GuaranteeInput input(long market, long deposit, long seniorDebt, HouseType houseType) {
        return new GuaranteeInput(market, deposit, seniorDebt, houseType, false, List.of(), true, true);
    }

    // ── 기준 픽스처 — V7 시드와 같은 값 ─────────────────────────────────────

    private static List<GuaranteeCriteriaSnapshot> seed() {
        return List.of(hug(new BigDecimal("90.00")), hf(), sgi());
    }

    /** HUG 보증료율은 미확정이라 시드에 행이 없다. */
    private static GuaranteeCriteriaSnapshot hug(BigDecimal collateralRatio) {
        return new GuaranteeCriteriaSnapshot(HUG, collateralRatio, new BigDecimal("60.00"), 700_000_000L,
                false, true, true, false, HUG_PRODUCT, List.of());
    }

    private static GuaranteeCriteriaSnapshot hf() {
        return new GuaranteeCriteriaSnapshot(HF, new BigDecimal("90.00"), null, 700_000_000L,
                false, true, true, true, HF_PRODUCT, List.of(
                band(HouseType.APARTMENT, "0.00", "70.00", "0.040"),
                band(HouseType.APARTMENT, "70.00", "80.00", "0.110"),
                band(HouseType.APARTMENT, "80.00", "90.00", "0.180"),
                band(HouseType.OTHER, "0.00", "70.00", "0.040"),
                band(HouseType.OTHER, "70.00", "80.00", "0.110"),
                band(HouseType.OTHER, "80.00", "90.00", "0.180")));
    }

    private static GuaranteeCriteriaSnapshot sgi() {
        return new GuaranteeCriteriaSnapshot(SGI, new BigDecimal("90.00"), null, 1_000_000_000L,
                true, true, true, false, SGI_PRODUCT, List.of(
                band(HouseType.APARTMENT, "0.00", "50.00", "0.160"),
                band(HouseType.APARTMENT, "50.00", "60.00", "0.183"),
                band(HouseType.APARTMENT, "60.00", "90.00", "0.229"),
                band(HouseType.OTHER, "0.00", "50.00", "0.182"),
                band(HouseType.OTHER, "50.00", "60.00", "0.208"),
                band(HouseType.OTHER, "60.00", "90.00", "0.260")));
    }

    private static PremiumRateBand band(HouseType houseType, String min, String max, String rate) {
        return new PremiumRateBand(houseType, 0L, null, new BigDecimal(min), new BigDecimal(max),
                new BigDecimal(rate));
    }

    // ── 기대 결과 ─────────────────────────────────────────────────────────

    private static ProviderJudgement ok(GuaranteeProvider provider, long limit, Long premium) {
        return new ProviderJudgement(provider, true, List.of(), provider == HF, limit, premium, productName(provider));
    }

    private static ProviderJudgement fail(GuaranteeProvider provider, long limit,
            GuaranteeFailedCondition... conditions) {
        return new ProviderJudgement(provider, false, List.of(conditions), provider == HF, limit, null, null);
    }

    private static String productName(GuaranteeProvider provider) {
        return switch (provider) {
            case HUG -> HUG_PRODUCT;
            case HF -> HF_PRODUCT;
            case SGI -> SGI_PRODUCT;
        };
    }
}
