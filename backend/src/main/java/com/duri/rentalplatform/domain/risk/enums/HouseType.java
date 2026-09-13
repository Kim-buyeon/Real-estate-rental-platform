package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보증료율 표의 주택유형. {@code guarantee_premium_rate.house_type} 의 값이다.
 *
 * <p>매물 유형({@code PropertyType})과 축이 다르다 — 요율 표는 아파트와 그 외(오피스텔 등 비아파트)로만 가른다.
 * 매물 유형에서 이 값으로 옮기는 것은 위험도 분석 서비스가 맡는다.
 */
@Getter
@RequiredArgsConstructor
public enum HouseType {
    APARTMENT("아파트"),
    OTHER("그 외");

    private final String label;
}
