package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 매물 상태. {@code property_code} 의 {@code PROPERTY_STATUS} 그룹 값과 같다.
 *
 * <p>실거래가 자료에는 거래 가능 여부가 없다. 초기 적재가 세팅하는 값은 {@link #AVAILABLE} 하나이며,
 * 상태를 바꾸는 기능이 생길 때 값을 늘린다.
 */
@Getter
@RequiredArgsConstructor
public enum PropertyStatus {
    AVAILABLE("거래가능");

    private final String label;
}
