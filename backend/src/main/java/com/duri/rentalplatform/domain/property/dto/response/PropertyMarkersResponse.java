package com.duri.rentalplatform.domain.property.dto.response;

import java.util.List;

/**
 * 반경 마커 목록. API 명세서(매물) 1.3 · 1.4 — 커서 없이 {@code items} + {@code count} 다. 반경 안 매물이 상한을 넘으면 가까운
 * 상한 건수만 담고 {@code truncated} 가 참이다.
 */
public record PropertyMarkersResponse(List<PropertyMarkerResponse> items, int count, boolean truncated) {

    public static PropertyMarkersResponse of(List<PropertyMarkerResponse> items, boolean truncated) {
        return new PropertyMarkersResponse(items, items.size(), truncated);
    }
}
