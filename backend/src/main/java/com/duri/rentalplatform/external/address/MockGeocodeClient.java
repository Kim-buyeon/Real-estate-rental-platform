package com.duri.rentalplatform.external.address;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 좌표 변환 Mock. 주소 문자열만으로 좌표를 만들어 <b>같은 주소는 항상 같은 좌표</b>가 되게 한다.
 *
 * <p>좌표를 난수나 시각에 걸면 적재를 다시 돌릴 때마다 같은 건물이 다른 자리에 찍혀 지도 화면을
 * 확인할 수 없다. 문자열 hashCode 는 자바 명세가 고정한 값이라 실행마다 같다.
 *
 * <p>범위는 서울 행정구역을 감싸는 사각형이다. 실제 주소의 위치는 아니지만 지도 탐색의 바운딩 박스
 * 조회와 자치구 단계 이동을 확인하기에는 충분하다.
 */
@Component
@ConditionalOnProperty(prefix = "external.geocode", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockGeocodeClient implements GeocodeClient {

    private static final String DATA_SOURCE = "MOCK_KAKAO_LOCAL";

    /** 서울을 감싸는 사각형. 남서 · 북동 모서리. */
    private static final double MIN_LATITUDE = 37.42;
    private static final double MAX_LATITUDE = 37.70;
    private static final double MIN_LONGITUDE = 126.76;
    private static final double MAX_LONGITUDE = 127.18;

    /** 스키마가 NUMERIC(10,7) 이므로 소수점 7자리로 맞춘다. */
    private static final int COORDINATE_SCALE = 7;

    @Override
    public Optional<Coordinates> geocode(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        int hash = address.hashCode();
        // 위도와 경도가 같은 비트에서 나오면 모든 점이 대각선 위에 놓인다. 상·하위 비트를 갈라 쓴다.
        double latitudeRatio = ratio(hash >>> 16);
        double longitudeRatio = ratio(hash & 0xFFFF);

        return Optional.of(new Coordinates(
                scaled(MIN_LATITUDE + (MAX_LATITUDE - MIN_LATITUDE) * latitudeRatio),
                scaled(MIN_LONGITUDE + (MAX_LONGITUDE - MIN_LONGITUDE) * longitudeRatio),
                DATA_SOURCE));
    }

    private double ratio(int bits) {
        return (bits & 0xFFFF) / (double) 0xFFFF;
    }

    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
