package com.duri.rentalplatform.domain.admin.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.domain.admin.dto.request.CriteriaHistoryRequest;
import com.duri.rentalplatform.domain.admin.dto.request.GuaranteeCriteriaUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.LoanRegulationUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.PremiumRateUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.RiskThresholdUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.response.CriteriaHistoryResponse;
import com.duri.rentalplatform.domain.admin.dto.response.GuaranteeCriteriaResponse;
import com.duri.rentalplatform.domain.admin.dto.response.LoanRegulationsResponse;
import com.duri.rentalplatform.domain.admin.dto.response.PremiumRatesResponse;
import com.duri.rentalplatform.domain.admin.dto.response.RiskThresholdResponse;
import com.duri.rentalplatform.domain.admin.service.CriteriaCommandService;
import com.duri.rentalplatform.domain.admin.service.CriteriaQueryService;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판정 기준 관리 {@code /api/admin/criteria} — API 명세서(관리자) 1장. 인증 「관리자」는 보안 설정의 경로 규칙이 건다.
 *
 * <p>수정은 트랜잭션이 끝난 뒤 같은 경로의 조회 결과를 돌려준다. 같은 트랜잭션에서 JPA 로 바꾼 값을 매퍼로 읽지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/criteria")
public class CriteriaController {

    private final CriteriaQueryService queryService;
    private final CriteriaCommandService commandService;

    @GetMapping("/guarantee")
    public ApiResponse<GuaranteeCriteriaResponse> getGuarantee() {
        return ApiResponse.ok(queryService.getGuaranteeCriteria());
    }

    @PutMapping("/guarantee/{provider}")
    public ApiResponse<GuaranteeCriteriaResponse> updateGuarantee(
            @AuthenticationPrincipal Long userId,
            @PathVariable GuaranteeProvider provider,
            @Valid @RequestBody GuaranteeCriteriaUpdateRequest request) {
        commandService.updateGuarantee(userId, provider, request);
        return ApiResponse.ok(queryService.getGuaranteeCriteria());
    }

    @GetMapping("/premium-rates")
    public ApiResponse<PremiumRatesResponse> getPremiumRates() {
        return ApiResponse.ok(queryService.getPremiumRates());
    }

    @PutMapping("/premium-rates")
    public ApiResponse<PremiumRatesResponse> updatePremiumRates(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PremiumRateUpdateRequest request) {
        commandService.updatePremiumRates(userId, request);
        return ApiResponse.ok(queryService.getPremiumRates());
    }

    @GetMapping("/loan-regulations")
    public ApiResponse<LoanRegulationsResponse> getLoanRegulations() {
        return ApiResponse.ok(queryService.getLoanRegulations());
    }

    @PutMapping("/loan-regulations")
    public ApiResponse<LoanRegulationsResponse> updateLoanRegulations(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody LoanRegulationUpdateRequest request) {
        commandService.updateLoanRegulations(userId, request);
        return ApiResponse.ok(queryService.getLoanRegulations());
    }

    @GetMapping("/risk-thresholds")
    public ApiResponse<RiskThresholdResponse> getRiskThreshold() {
        return ApiResponse.ok(queryService.getRiskThreshold());
    }

    @PutMapping("/risk-thresholds")
    public ApiResponse<RiskThresholdResponse> updateRiskThreshold(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody RiskThresholdUpdateRequest request) {
        commandService.updateRiskThreshold(userId, request);
        return ApiResponse.ok(queryService.getRiskThreshold());
    }

    @GetMapping("/history")
    public ApiResponse<CursorPage<CriteriaHistoryResponse>> getHistory(
            @Valid @ModelAttribute CriteriaHistoryRequest request) {
        return ApiResponse.ok(queryService.getHistory(request));
    }
}
