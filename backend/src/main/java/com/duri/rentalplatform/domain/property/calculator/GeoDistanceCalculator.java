package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.vo.BoundingBox;

/**
 * 반경 검색의 거리 계산. 영속성 구조 1.3 — 반경을 바운딩 박스로 바꿔 1차 필터하고 Haversine 으로 확정한다.
 *
 * <p>공간 확장 없이 애플리케이션에서 계산한다. 빈이 아닌 순수 함수다.
 */
public final class GeoDistanceCalculator {

    /** 지구 평균 반지름(km). IUGG 평균 반지름 R1. */
    static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistanceCalculator() {
    }

    /** 두 좌표 사이의 대원 거리(km). */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 중심에서 반경 안의 모든 점을 포함하는 사각 범위. 반경 원에 외접하므로 모서리 쪽에는 반경 밖 점이
     * 섞인다 — 최종 판정은 {@link #haversineKm}이 한다.
     */
    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        double latDelta = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
        double cosLat = Math.cos(Math.toRadians(lat));
        // 극점 근처에서 경도 폭이 발산하지 않게 막는다. 서울 범위에서는 걸리지 않는다.
        double lngDelta = cosLat < 1e-9 ? 180.0 : Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cosLat));
        return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
    }
}
