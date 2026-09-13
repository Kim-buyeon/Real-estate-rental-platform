package com.duri.rentalplatform.domain.property.vo;

/**
 * 자치구 집계 매퍼 행. 응답의 {@code gradeCounts} 는 중첩 객체라 평면으로 받고 응답의 정적 팩토리가 묶는다.
 */
public record DistrictCountRow(
        String name,
        Long totalCount,
        Long safeCount,
        Long cautionCount,
        Long dangerCount
) {
}
