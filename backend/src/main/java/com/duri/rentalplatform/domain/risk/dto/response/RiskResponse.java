package com.duri.rentalplatform.domain.risk.dto.response;

import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyResult;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeJudgementResult;
import com.duri.rentalplatform.domain.risk.vo.NegativeEquityResult;
import com.duri.rentalplatform.domain.risk.vo.ProviderJudgement;
import com.duri.rentalplatform.domain.risk.vo.RightViolationResult;
import com.duri.rentalplatform.domain.risk.vo.RiskGradeResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 위험 등급과 판정 근거. API 명세서(위험도 분석) 1.1.
 *
 * <p>근거는 최신 분석 행이 판정 때 적어 둔 것({@link Judgement}) 또는 이번에 수행한 계산 결과다 — 어느 쪽인지는
 * {@code RiskAnalysisCommandService} 가 정한다. 시세 · 시세 구분 · 기준일은 늘 지금 매물의 값이고, {@code analyzedAt} 은 최신
 * 분석 행의 시각이다.
 */
public record RiskResponse(
        RiskGrade riskGrade,
        GradeReason gradeReason,
        BigDecimal debtRatio,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate,
        long seniorDebtTotal,
        boolean isNegativeEquity,
        boolean insuranceEligible,
        List<Provider> providers,
        List<PersonalCondition> personalConditions,
        List<OwnershipRightType> rightViolations,
        List<OwnershipRightType> warnings,
        Consistency consistency,
        OffsetDateTime analyzedAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 기관별 가입 판정. HUG → HF → SGI 순. */
    public record Provider(
            GuaranteeProvider provider,
            boolean eligible,
            List<GuaranteeFailedCondition> failedConditions,
            boolean loanLinkRequired,
            long guaranteeLimit,
            Long estimatedPremium,
            String productName
    ) {

        static Provider from(ProviderJudgement judgement) {
            return new Provider(judgement.provider(), judgement.eligible(), judgement.failedConditions(),
                    judgement.loanLinkRequired(), judgement.guaranteeLimit(), judgement.estimatedPremium(),
                    judgement.productName());
        }
    }

    /**
     * 명의 · 문서 정합 확인(RISK-04).
     *
     * <p>대장에서 오는 세 항목({@code addressMatched} · {@code violationBuilding} · {@code areaMatched})은 null 이 「확인
     * 불가」다 — 뗄 대장이 없거나 대장이 그 항목을 주지 않을 때. 참 · 거짓으로 바꾸지 않고 null 그대로 낸다.
     */
    public record Consistency(
            boolean ownerNameMatched,
            Boolean addressMatched,
            Boolean violationBuilding,
            Boolean areaMatched
    ) {

        static Consistency from(ConsistencyResult result) {
            return new Consistency(result.ownerNameMatched(), result.addressMatched(), result.violationBuilding(),
                    result.areaMatched());
        }
    }

    /**
     * 판정 근거 — 이 응답에서 시세 · 시세 구분 · 기준일 · 분석 시각을 뺀 부분. 최신 분석 행이 JSON 으로 저장한다(V21).
     *
     * <p><b>뺀 넷</b> — 시세 셋은 판정 뒤에도 매물에서 바뀔 수 있는 표시 값이라 조회 때 매물에서 읽는다(금액이 바뀌면 재분석
     * 대기 표시가 서서 저장된 근거를 쓰지 않는다). 분석 시각은 행의 {@code analyzed_at} 이다. 근거에 넣으면 결론이 같은 판정마다
     * 근거가 달라져 행을 다시 쓴다.
     *
     * <p><b>판정 결과 값 객체가 아니라 응답 모양으로 저장하는 이유</b> — 응답은 명세가 정한 계약이라 계산기 내부 값 객체보다 덜
     * 바뀐다. 응답 필드를 바꾸면 이 record 와 {@link RiskResponse#of(Judgement, Long, PriceType, LocalDate, LocalDateTime)} 가
     * 함께 컴파일되지 않아 드러난다. 모양을 바꾸면 기준 지문의 형식 판(版)도 올린다 — 옛 모양의 근거가 다시 판정되도록
     * ({@code CriteriaFingerprintCalculator}).
     */
    public record Judgement(
            RiskGrade riskGrade,
            GradeReason gradeReason,
            BigDecimal debtRatio,
            long seniorDebtTotal,
            boolean isNegativeEquity,
            boolean insuranceEligible,
            List<Provider> providers,
            List<PersonalCondition> personalConditions,
            List<OwnershipRightType> rightViolations,
            List<OwnershipRightType> warnings,
            Consistency consistency
    ) {
    }

    /**
     * 이번에 수행한 판정 결과들을 묶는다.
     *
     * @param analyzedAt 최신 분석 행의 시각. DB 는 서울 벽시계 시각이라 서울 오프셋을 붙인다 — 공통 규약 1.1
     */
    public static RiskResponse of(
            RiskGradeResult grade,
            NegativeEquityResult negativeEquity,
            GuaranteeJudgementResult guarantee,
            RightViolationResult rights,
            ConsistencyResult consistency,
            Long marketPrice,
            PriceType priceType,
            LocalDate priceDate,
            LocalDateTime analyzedAt) {
        Judgement judgement = new Judgement(
                grade.riskGrade(),
                grade.gradeReason(),
                negativeEquity.debtRatio(),
                negativeEquity.seniorDebtTotal(),
                negativeEquity.negativeEquity(),
                guarantee.insuranceEligible(),
                guarantee.providers().stream().map(Provider::from).toList(),
                guarantee.personalConditions(),
                rights.rightViolations(),
                rights.warnings(),
                Consistency.from(consistency));
        return of(judgement, marketPrice, priceType, priceDate, analyzedAt);
    }

    /**
     * 판정 근거에 지금 매물의 시세와 분석 시각을 붙인다. 저장된 근거로 응답할 때와 이번 판정으로 응답할 때가 같은 길을 지난다.
     *
     * @param analyzedAt 최신 분석 행의 시각. DB 는 서울 벽시계 시각이라 서울 오프셋을 붙인다 — 공통 규약 1.1
     */
    public static RiskResponse of(
            Judgement judgement,
            Long marketPrice,
            PriceType priceType,
            LocalDate priceDate,
            LocalDateTime analyzedAt) {
        return new RiskResponse(
                judgement.riskGrade(),
                judgement.gradeReason(),
                judgement.debtRatio(),
                marketPrice,
                priceType,
                priceDate,
                judgement.seniorDebtTotal(),
                judgement.isNegativeEquity(),
                judgement.insuranceEligible(),
                judgement.providers(),
                judgement.personalConditions(),
                judgement.rightViolations(),
                judgement.warnings(),
                judgement.consistency(),
                analyzedAt == null ? null : analyzedAt.atZone(SEOUL).toOffsetDateTime());
    }

    /** 저장할 판정 근거. */
    public Judgement judgement() {
        return new Judgement(riskGrade, gradeReason, debtRatio, seniorDebtTotal, isNegativeEquity, insuranceEligible,
                providers, personalConditions, rightViolations, warnings, consistency);
    }
}
