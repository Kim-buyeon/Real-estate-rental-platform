package com.duri.rentalplatform.domain.property.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 건축물대장 조회. API 명세서(매물) 1.8. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final LedgerMapper ledgerMapper;

    /**
     * 수집된 대장을 읽는다. 수집은 {@link LedgerCommandService#collectIfAbsent} 가 먼저 끝낸다 — 매물이 없으면 거기서
     * {@link ErrorCode#PROPERTY_NOT_FOUND}, 연동이 실패하면 {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 이 난다. 그래서
     * 여기서 대장이 없다는 것은 매물이 없다는 뜻이다.
     */
    public LedgerResponse getLedger(Long propertyId) {
        LedgerRow row = ledgerMapper.selectLedger(propertyId);
        if (row == null) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        return LedgerResponse.of(row);
    }
}
