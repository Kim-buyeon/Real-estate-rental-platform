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
 * <p>근거는 저장 행이 아니라 이번에 수행한 계산 결과다 — 분석 행은 결론만 담는다. {@code analyzedAt} 만 최신 분석 행의
 * 시각이다.
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

    /** 명의 · 문서 정합 확인(RISK-04). */
    public record Consistency(
            boolean ownerNameMatched,
            boolean addressMatched,
            boolean violationBuilding,
            boolean areaMatched
    ) {

        static Consistency from(ConsistencyResult result) {
            return new Consistency(result.ownerNameMatched(), result.addressMatched(), result.violationBuilding(),
                    result.areaMatched());
        }
    }

    /**
     * 판정 결과들을 묶는다.
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
        return new RiskResponse(
                grade.riskGrade(),
                grade.gradeReason(),
                negativeEquity.debtRatio(),
                marketPrice,
                priceType,
                priceDate,
                negativeEquity.seniorDebtTotal(),
                negativeEquity.negativeEquity(),
                guarantee.insuranceEligible(),
                guarantee.providers().stream().map(Provider::from).toList(),
                guarantee.personalConditions(),
                rights.rightViolations(),
                rights.warnings(),
                Consistency.from(consistency),
                analyzedAt == null ? null : analyzedAt.atZone(SEOUL).toOffsetDateTime());
    }
}
