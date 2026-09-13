package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.dto.response.RegistryResponse;
import com.duri.rentalplatform.domain.risk.mapper.RegistryMapper;
import com.duri.rentalplatform.domain.risk.vo.RegistryHeaderRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 등기 이력 조회. API 명세서(위험도 분석) 1.3. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistryQueryService {

    private final RegistryMapper registryMapper;

    /**
     * 수집된 등기를 읽는다. 수집은 {@link RegistryCommandService#collectIfAbsent} 가 먼저 끝낸다 — 매물이
     * 없으면 거기서 {@link ErrorCode#PROPERTY_NOT_FOUND} 가 난다. 그래서 여기서 표제부가 없다는 것도 매물이
     * 없다는 뜻이다.
     */
    public RegistryResponse getRegistry(Long propertyId) {
        RegistryHeaderRow header = registryMapper.selectHeader(propertyId);
        if (header == null) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        return RegistryResponse.of(
                header,
                registryMapper.selectOwnerships(propertyId),
                registryMapper.selectMortgages(propertyId));
    }
}
