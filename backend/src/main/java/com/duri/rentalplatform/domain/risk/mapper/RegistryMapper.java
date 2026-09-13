package com.duri.rentalplatform.domain.risk.mapper;

import com.duri.rentalplatform.domain.risk.dto.response.RegistryResponse;
import com.duri.rentalplatform.domain.risk.vo.RegistryHeaderRow;
import java.util.List;

/**
 * 등기 이력 조회. XML 은 {@code resources/mapper/risk/RegistryMapper.xml}.
 *
 * <p>조회 조건이 매물 ID 하나라 Condition 을 두지 않는다. 요청 값이 가공 없이 그대로 조건이다.
 */
public interface RegistryMapper {

    /** 표제부. 수집하지 않은 매물이면 null. */
    RegistryHeaderRow selectHeader(Long propertyId);

    /** 갑구. 접수일 → 순위번호 순. */
    List<RegistryResponse.Ownership> selectOwnerships(Long propertyId);

    /** 을구. 접수일 → 순위번호 순. */
    List<RegistryResponse.Mortgage> selectMortgages(Long propertyId);
}
