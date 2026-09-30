package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.LedgerSourceTargetCondition;
import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import java.util.List;

/**
 * 건축물대장 조회. XML 은 {@code resources/mapper/property/LedgerMapper.xml}.
 *
 * <p>{@link #selectLedger} 는 조회 조건이 매물 ID 하나라 Condition 을 두지 않는다. 요청 값이 가공 없이 그대로 조건이다.
 */
public interface LedgerMapper {

    /** 수집된 대장. 수집하지 않은 매물이면 null. */
    LedgerRow selectLedger(Long propertyId);

    /**
     * 대장의 수집 출처가 조건과 같은 매물 식별자 한 페이지. {@code property_id ASC}. 배치용 대량 조회다 — 아키텍처
     * 설계서(영속성 구조) 1.1. Mock 대장 교체 배치가 관심 매물만 · 전체를 차례로 읽는다.
     */
    List<Long> selectPropertyIdsByLedgerSource(LedgerSourceTargetCondition condition);
}
