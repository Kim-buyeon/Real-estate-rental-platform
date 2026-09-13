package com.duri.rentalplatform.domain.risk.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위험도 조회. API 명세서(위험도 분석) 1장 RISK-01 · RISK-05. 인증 「선택」이지만 개인화 필드가 없어 인증 사용자를
 * 받지 않는다.
 *
 * <p>조회 시점에 분석을 수행한다 — 데이터 적재 설계서 1.2 「판정은 매물 상세 조회 시점에 수행」. 결과가 바뀌었을 때만
 * 이력이 쌓이는 분기는 서비스에 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/properties/{propertyId}/risk")
public class RiskController {

    private final RiskAnalysisCommandService commandService;

    @GetMapping
    public ApiResponse<RiskResponse> risk(@PathVariable Long propertyId) {
        return ApiResponse.ok(commandService.analyze(propertyId));
    }
}
