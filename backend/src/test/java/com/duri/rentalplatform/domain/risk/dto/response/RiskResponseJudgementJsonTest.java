package com.duri.rentalplatform.domain.risk.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장하는 판정 근거({@link RiskResponse.Judgement})가 JSON 으로 적고 읽어도 같은 값인가. 저장된 근거를 응답으로 돌려주므로 한 필드라도
 * 왕복에서 바뀌면 조회가 판정과 다른 응답을 낸다. 거짓이 기본값인 불리언이나 null 이 기본값인 필드는 읽기가 실패해도 우연히 같아 보이므로
 * 참 · 값 있음 쪽으로 채워 확인한다.
 */
class RiskResponseJudgementJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("모든 필드를 기본값이 아닌 쪽으로 채운 근거가 JSON 왕복 뒤에도 같다 — 깡통전세 참 · 확인 불가 null 포함")
    void roundTripPreservesEveryField() {
        RiskResponse.Judgement original = new RiskResponse.Judgement(
                RiskGrade.DANGER,
                GradeReason.NEGATIVE_EQUITY,
                new BigDecimal("116.70"),
                95_000_000L,
                true,
                false,
                List.of(
                        new RiskResponse.Provider(GuaranteeProvider.HUG, false,
                                List.of(GuaranteeFailedCondition.VIOLATION_BUILDING), false, 0L, null,
                                "전세보증금반환보증"),
                        new RiskResponse.Provider(GuaranteeProvider.HF, true, List.of(), true, 120_000_000L,
                                350_000L, null)),
                List.of(PersonalCondition.values()[0]),
                List.of(OwnershipRightType.values()[0]),
                List.of(OwnershipRightType.PROVISIONAL_REGISTRATION),
                new RiskResponse.Consistency(true, null, true, null));

        String json = jsonMapper.writeValueAsString(original);
        RiskResponse.Judgement restored = jsonMapper.readValue(json, RiskResponse.Judgement.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isNegativeEquity()).isTrue();
        assertThat(restored.consistency().addressMatched()).isNull();
        assertThat(restored.consistency().violationBuilding()).isTrue();
    }

    @Test
    @DisplayName("분석 시각 · 시세 셋은 저장 JSON 에 들어가지 않는다 — 근거가 같은 판정마다 같은 문자열이다")
    void snapshotHasNoPriceOrAnalyzedAt() {
        RiskResponse.Judgement judgement = new RiskResponse.Judgement(RiskGrade.SAFE,
                GradeReason.INSURANCE_ELIGIBLE, new BigDecimal("50.00"), 0L, false, true, List.of(), List.of(),
                List.of(), List.of(), new RiskResponse.Consistency(true, true, false, true));

        String json = jsonMapper.writeValueAsString(judgement);

        assertThat(json).doesNotContain("analyzedAt").doesNotContain("marketPrice").doesNotContain("priceType")
                .doesNotContain("priceDate");
        assertThat(jsonMapper.writeValueAsString(judgement)).isEqualTo(json);
    }
}
