package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.HouseType;
import java.math.BigDecimal;

/**
 * 보증료율 표의 한 행 — {@code guarantee_premium_rate}.
 *
 * <p>보증금은 {@code depositMin ≤ 보증금 ≤ depositMax}, 전세가율은 {@code debtRatioMin < 전세가율 ≤ debtRatioMax}
 * 에 들어가면 이 행이다. {@code debtRatioMin} 이 0 이면 0 을 포함한다.
 *
 * @param houseType    주택유형
 * @param depositMin   보증금 하한(원, 포함)
 * @param depositMax   보증금 상한(원, 포함). null 이면 상한 없음
 * @param debtRatioMin 전세가율 하한(%, 제외 — 0 이면 포함)
 * @param debtRatioMax 전세가율 상한(%, 포함)
 * @param premiumRate  연 보증료율(%)
 */
public record PremiumRateBand(
        HouseType houseType,
        long depositMin,
        Long depositMax,
        BigDecimal debtRatioMin,
        BigDecimal debtRatioMax,
        BigDecimal premiumRate
) {
}
