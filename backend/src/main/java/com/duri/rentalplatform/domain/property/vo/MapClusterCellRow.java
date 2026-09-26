package com.duri.rentalplatform.domain.property.vo;

import java.math.BigDecimal;

/**
 * 지도 묶음 격자 한 칸의 집계 행(명세 1.12). 응답의 {@code gradeCounts} · 칸 경계는 중첩 객체 · 계산 값이라
 * 평면으로 받고 응답의 정적 팩토리가 묶는다.
 *
 * @param representativeId 칸 안 매물 중 가장 작은 식별자. 한 건뿐인 칸은 그 매물이다
 */
public record MapClusterCellRow(
        Integer rowIndex,
        Integer colIndex,
        Long count,
        BigDecimal latitude,
        BigDecimal longitude,
        Long safeCount,
        Long cautionCount,
        Long dangerCount,
        Long unanalyzedCount,
        Long representativeId
) {
}
