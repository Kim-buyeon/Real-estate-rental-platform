package com.duri.rentalplatform.external.address;

import java.math.BigDecimal;

/**
 * 위경도. {@code property.latitude} · {@code longitude} 에 저장한다.
 *
 * <p>스키마가 NUMERIC(10,7) 이므로 {@link BigDecimal} 로 받는다. 좌표는 적재 시점에 확보한다 —
 * 조회 시점에 변환하면 지도 마커가 매번 외부를 호출하게 된다.
 *
 * @param latitude   위도
 * @param longitude  경도
 * @param dataSource 출처 표기
 */
public record Coordinates(
        BigDecimal latitude,
        BigDecimal longitude,
        String dataSource
) {
}
