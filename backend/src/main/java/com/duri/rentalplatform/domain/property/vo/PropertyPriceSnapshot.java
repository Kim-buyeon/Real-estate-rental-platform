package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.PriceType;
import java.time.LocalDate;

/**
 * 이미 적재된 매물의 저장된 시세. 갱신 배치(RISK-08)가 새로 계산한 시세와 견주어, 값이 다른 매물만 갱신하는 비교 대상이다 —
 * 데이터 적재 설계서 1.5.
 *
 * @param propertyId  매물 식별자. 갱신할 행을 가리킨다
 * @param marketPrice 저장된 시세(원)
 * @param priceType   저장된 시세 산출 근거
 * @param priceDate   저장된 시세 기준일
 */
public record PropertyPriceSnapshot(
        Long propertyId,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate
) {
}
