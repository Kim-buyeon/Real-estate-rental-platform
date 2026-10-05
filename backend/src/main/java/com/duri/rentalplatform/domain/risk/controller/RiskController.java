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
 * <p>조회는 인증 「선택」이지만 개인화 필드가 없어 인증 사용자를 받지 않는다. 최신 판정을 돌려준다 — 처음 조회하는 매물이거나
 * 판정 입력이 바뀌었으면(기준 변경 · 시세 변경 · 근거 없음) 그 자리에서 판정한다. 조건은 데이터 적재 설계서 1.2, 그 분기와
 * 결과가 바뀌었을 때만 이력이 쌓이는 분기는 서비스에 있다.
 *
 * <p>재분석은 인증 「필수」다. 저장된 판정과 무관하게 늘 다시 판정한다. 보안 설정이 이 접두에서 GET 만 열어 두므로 POST 는 인증 규칙을 따른다. 간격 · 락이 매물 단위라
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
        return ApiResponse.ok(commandService.findLatestOrAnalyze(propertyId));
    }

    @PostMapping("/reanalyze")
    public ApiResponse<RiskReanalyzeResponse> reanalyze(@PathVariable Long propertyId) {
        return ApiResponse.ok(reanalysisCommandService.reanalyze(propertyId));
    }
}
