package com.duri.rentalplatform.domain.property.vo;

import java.math.BigDecimal;

/**
 * 반경 조회의 후보 한 건 — 바운딩 박스 안 매물의 식별자 · 좌표(명세 1.3). 서비스가 거리로 걸러 거리순으로 상한 건수만 남기고,
 * 남은 식별자로 마커 컬럼을 따로 읽는다.
 */
public record MarkerCandidateRow(
        Long propertyId,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
