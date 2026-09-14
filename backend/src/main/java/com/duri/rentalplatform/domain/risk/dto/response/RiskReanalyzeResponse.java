package com.duri.rentalplatform.domain.risk.dto.response;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.time.OffsetDateTime;

/**
 * 재분석 결과. API 명세서(위험도 분석) 1.4.
 *
 * @param previousGrade 요청을 받은 시점의 최신 분석 등급. 분석된 적이 없으면 null
 * @param gradeChanged  직전 등급이 있고 이번 등급과 다를 때만 참. 첫 분석은 「변경」이 아니다
 * @param analyzedAt    최신 분석 행의 시각(서울 오프셋)
 */
public record RiskReanalyzeResponse(
        Long propertyId,
        RiskGrade previousGrade,
        RiskGrade riskGrade,
        boolean gradeChanged,
        OffsetDateTime analyzedAt
) {

    public static RiskReanalyzeResponse of(Long propertyId, RiskGrade previousGrade, RiskResponse analyzed) {
        RiskGrade riskGrade = analyzed.riskGrade();
        return new RiskReanalyzeResponse(
                propertyId,
                previousGrade,
                riskGrade,
                previousGrade != null && previousGrade != riskGrade,
                analyzed.analyzedAt());
    }
}
