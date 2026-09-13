package com.duri.rentalplatform.domain.property.dto.response;

import java.util.List;

/**
 * 지도 마커 목록. API 명세서(매물) 1.3 · 1.4 — 표시 영역 전체를 그리므로 커서 없이 {@code items} +
 * {@code count} 다.
 */
public record PropertyMarkersResponse(List<PropertyMarkerResponse> items, int count) {

    public static PropertyMarkersResponse of(List<PropertyMarkerResponse> items) {
        return new PropertyMarkersResponse(items, items.size());
    }
}
