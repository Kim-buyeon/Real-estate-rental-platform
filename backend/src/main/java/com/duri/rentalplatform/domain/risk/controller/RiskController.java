package com.duri.rentalplatform.domain.risk.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.risk.dto.response.RiskReanalyzeResponse;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import com.duri.rentalplatform.domain.risk.service.RiskReanalysisCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위험도 조회와 재분석 요청. API 명세서(위험도 분석) 1장 RISK-01 · RISK-05 · RISK-08.
 *
 * <p>조회는 인증 「선택」이지만 개인화 필드가 없어 인증 사용자를 받지 않는다. 조회 시점에 분석을 수행한다 — 데이터 적재 설계서 1.2
 * 「판정은 매물 상세 조회 시점에 수행」. 결과가 바뀌었을 때만 이력이 쌓이는 분기는 서비스에 있다.
 *
 * <p>재분석은 인증 「필수」다. 보안 설정이 이 접두에서 GET 만 열어 두므로 POST 는 인증 규칙을 따른다. 간격 · 락이 매물 단위라
 * 서비스가 사용자를 쓰지 않아 인증 사용자를 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/properties/{propertyId}/risk")
public class RiskController {

    private final RiskAnalysisCommandService commandService;
    private final RiskReanalysisCommandService reanalysisCommandService;

    @GetMapping
    public ApiResponse<RiskResponse> risk(@PathVariable Long propertyId) {
        return ApiResponse.ok(commandService.analyze(propertyId));
    }

    @PostMapping("/reanalyze")
    public ApiResponse<RiskReanalyzeResponse> reanalyze(@PathVariable Long propertyId) {
        return ApiResponse.ok(reanalysisCommandService.reanalyze(propertyId));
    }
}
