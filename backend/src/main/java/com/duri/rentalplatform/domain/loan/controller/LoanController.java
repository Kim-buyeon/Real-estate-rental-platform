package com.duri.rentalplatform.domain.loan.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.loan.dto.response.LoanLimitResponse;
import com.duri.rentalplatform.domain.loan.service.LoanCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대출 경로 {@code /api/loans}. API 명세서(대출) 1장. LOAN-01 한도 계산 — 인증 「필수」(보안 설정의 기본 규칙).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanCommandService commandService;

    @GetMapping("/limit")
    public ApiResponse<LoanLimitResponse> limit(
            @AuthenticationPrincipal Long userId,
            @RequestParam Long propertyId) {
        return ApiResponse.ok(commandService.calculateLimit(userId, propertyId));
    }
}
