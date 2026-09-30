package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.PriceType;
import java.time.LocalDate;

/**
 * 기존 매물 한 건에 반영할 새 시세. 갱신 배치(RISK-08)가 저장값과 다르다고 본 매물만 만든다.
 *
 * @param propertyId  매물 식별자
 * @param marketPrice 새 시세(원)
 * @param priceType   새 시세 산출 근거
 * @param priceDate   새 시세 기준일
 */
public record MarketPriceUpdate(
        Long propertyId,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate
) {
}
