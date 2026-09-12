package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 시세({@code market_price}) 산출 근거. {@code property.price_type} 에 저장한다.
 *
 * <p>개발 환경 설계서 1.2 는 시세를 「실거래가 평균 또는 공시가격으로 대체하고 price_type · price_date 에
 * 산출 근거를 기록」하도록 정했다. 지금 적재 경로가 쓰는 근거는 실거래가 하나뿐이다.
 * 값 이름은 API 명세서(매물) 1.7 응답 예시의 {@code "priceType": "ACTUAL_TRANSACTION"} 과 맞춘다.
 */
@Getter
@RequiredArgsConstructor
public enum PriceType {
    ACTUAL_TRANSACTION("실거래가");

    private final String label;
}
