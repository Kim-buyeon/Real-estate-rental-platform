package com.duri.rentalplatform.domain.property.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.property.service.LedgerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 건축물대장 정보. API 명세서(매물) 1장 PROP-04. 인증 「선택」이지만 개인화 필드가 없어 인증 사용자를 받지 않는다.
 *
 * <p>수집과 조회를 두 서비스로 나눠 차례로 부른다. 수집은 외부 호출과 쓰기 트랜잭션이고 조회는 읽기 전용 트랜잭션이라
 * 한 서비스 메서드에 담을 수 없다. 둘 다 조건 없이 부르므로 분기는 서비스에 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/properties/{propertyId}/ledger")
public class LedgerController {

    private final LedgerCommandService commandService;
    private final LedgerQueryService queryService;

    @GetMapping
    public ApiResponse<LedgerResponse> ledger(@PathVariable Long propertyId) {
        commandService.collectIfAbsent(propertyId);
        return ApiResponse.ok(queryService.getLedger(propertyId));
    }
}
