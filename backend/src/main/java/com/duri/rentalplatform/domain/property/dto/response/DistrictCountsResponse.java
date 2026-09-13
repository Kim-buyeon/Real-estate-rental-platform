package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.DistrictCountRow;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 자치구 집계. API 명세서(매물) 1.5.
 *
 * <p>{@code gradeCounts} 는 SAFE · CAUTION · DANGER 세 키다. 미분석 매물은 어느 등급에도 들지 않고
 * {@code count} 에만 포함된다.
 */
public record DistrictCountsResponse(
        List<District> districts,
        long totalCount,
        OffsetDateTime aggregatedAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 집계 시각을 서울 오프셋으로 맞춘다. 캐시에서 역직렬화하면 Jackson 이 문맥 시간대(UTC)로 바꿔 읽는다.
     */
    public DistrictCountsResponse {
        if (aggregatedAt != null) {
            aggregatedAt = aggregatedAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
        }
    }

    public record District(String name, long count, Map<RiskGrade, Long> gradeCounts) {

        static District from(DistrictCountRow row) {
            Map<RiskGrade, Long> gradeCounts = new EnumMap<>(RiskGrade.class);
            gradeCounts.put(RiskGrade.SAFE, nz(row.safeCount()));
            gradeCounts.put(RiskGrade.CAUTION, nz(row.cautionCount()));
            gradeCounts.put(RiskGrade.DANGER, nz(row.dangerCount()));
            return new District(row.name(), nz(row.totalCount()), gradeCounts);
        }
    }

    public static DistrictCountsResponse of(List<DistrictCountRow> rows, OffsetDateTime aggregatedAt) {
        List<District> districts = rows.stream().map(District::from).toList();
        long total = districts.stream().mapToLong(District::count).sum();
        return new DistrictCountsResponse(districts, total, aggregatedAt);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
