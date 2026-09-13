package com.duri.rentalplatform.domain.property.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoDistanceCalculatorTest {

    @Test
    @DisplayName("같은 좌표의 거리는 0이다")
    void zeroDistance() {
        assertThat(GeoDistanceCalculator.haversineKm(37.55, 126.85, 37.55, 126.85)).isZero();
    }

    @Test
    @DisplayName("위도 1도 차이는 약 111.2km 다")
    void oneDegreeLatitude() {
        assertThat(GeoDistanceCalculator.haversineKm(37.0, 127.0, 38.0, 127.0))
                .isCloseTo(111.195, within(0.01));
    }

    @Test
    @DisplayName("서울시청 — 강남역 거리는 약 8.8km 다")
    void knownSeoulDistance() {
        // 서울시청(37.5663, 126.9779) — 강남역(37.4979, 127.0276)
        assertThat(GeoDistanceCalculator.haversineKm(37.5663, 126.9779, 37.4979, 127.0276))
                .isCloseTo(8.78, within(0.15));
    }

    @Test
    @DisplayName("바운딩 박스는 반경 거리의 동서남북 끝점을 모두 포함한다")
    void boundingBoxContainsRadiusExtremes() {
        double lat = 37.55;
        double lng = 126.85;
        double radius = 1.0;
        BoundingBox box = GeoDistanceCalculator.boundingBox(lat, lng, radius);

        assertThat(GeoDistanceCalculator.haversineKm(lat, lng, box.maxLat(), lng)).isCloseTo(radius, within(1e-6));
        assertThat(GeoDistanceCalculator.haversineKm(lat, lng, box.minLat(), lng)).isCloseTo(radius, within(1e-6));
        // 경도 끝점은 중심 위도에서 반경 이상 떨어져 있어야 원이 박스 안에 든다.
        assertThat(GeoDistanceCalculator.haversineKm(lat, lng, lat, box.maxLng())).isGreaterThanOrEqualTo(radius - 1e-6);
        assertThat(GeoDistanceCalculator.haversineKm(lat, lng, lat, box.minLng())).isGreaterThanOrEqualTo(radius - 1e-6);
    }

    @Test
    @DisplayName("박스 모서리는 반경 밖이다 — 최종 판정은 거리 계산이 한다")
    void boundingBoxCornerIsOutsideRadius() {
        BoundingBox box = GeoDistanceCalculator.boundingBox(37.55, 126.85, 1.0);

        assertThat(GeoDistanceCalculator.haversineKm(37.55, 126.85, box.maxLat(), box.maxLng()))
                .isGreaterThan(1.0);
    }
}
