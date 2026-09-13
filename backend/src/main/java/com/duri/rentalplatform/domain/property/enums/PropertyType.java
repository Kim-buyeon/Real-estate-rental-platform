package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 매물 유형. {@code property_code} 의 {@code PROPERTY_TYPE} 그룹 값과 같다.
 *
 * <p>적재 경로가 만들어 내는 값만 둔다. 국토교통부 전월세 실거래가는 주택 유형마다 서비스가 나뉘어
 * 있고, 지금 붙은 것은 아파트 · 오피스텔 둘뿐이다. 연립다세대 · 단독다가구는 그 서비스를 붙이는
 * 변경에서 시드와 함께 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum PropertyType {
    APARTMENT("아파트"),
    OFFICETEL("오피스텔");

    private final String label;
}
