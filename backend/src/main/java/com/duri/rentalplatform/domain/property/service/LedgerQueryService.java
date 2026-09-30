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
     * {@link ErrorCode#PROPERTY_NOT_FOUND}, 연동이 실패하면 {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} 이 난다.
     *
     * <p>행이 없으면 매물이 없는 것이다. 행은 있는데 대장 항목이 전부 비면 뗄 대장이 없는 매물이다 — 오류가 아니라 대장 항목이
     * 빈 응답(확인 불가)으로 낸다. 공통 규약 2장에 「대장 없음」에 맞는 코드가 없다.
     */
    public LedgerResponse getLedger(Long propertyId) {
        LedgerRow row = ledgerMapper.selectLedger(propertyId);
        if (row == null) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        return LedgerResponse.of(row);
    }
}
