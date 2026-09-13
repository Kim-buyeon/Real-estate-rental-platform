package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.vo.LedgerRow;

/**
 * 건축물대장 조회. XML 은 {@code resources/mapper/property/LedgerMapper.xml}.
 *
 * <p>조회 조건이 매물 ID 하나라 Condition 을 두지 않는다. 요청 값이 가공 없이 그대로 조건이다.
 */
public interface LedgerMapper {

    /** 수집된 대장. 수집하지 않은 매물이면 null. */
    LedgerRow selectLedger(Long propertyId);
}
