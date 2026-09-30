package com.duri.rentalplatform.domain.property.mapper;

import com.duri.rentalplatform.domain.property.dto.condition.LedgerReplaceTargetCondition;
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
     * Mock 대장 교체 배치 대상 매물 식별자 한 페이지 — 대장 출처가 조건과 같은 매물 · 대장 행이 없고 조회 키가 있는 매물.
     * {@code property_id ASC}. 배치용 대량 조회다 — 아키텍처 설계서(영속성 구조) 1.1. 배치가 관심 매물(둘 다) → Mock 전체 →
     * 대장 없음 전체 순서로 읽는다.
     */
    List<Long> selectLedgerReplaceTargetIds(LedgerReplaceTargetCondition condition);
}
