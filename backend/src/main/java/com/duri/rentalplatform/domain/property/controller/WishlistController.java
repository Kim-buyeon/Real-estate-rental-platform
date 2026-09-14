package com.duri.rentalplatform.domain.property.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.domain.property.dto.request.WishlistAddRequest;
import com.duri.rentalplatform.domain.property.dto.request.WishlistListRequest;
import com.duri.rentalplatform.domain.property.dto.response.WishlistResponse;
import com.duri.rentalplatform.domain.property.service.WishlistCommandService;
import com.duri.rentalplatform.domain.property.service.WishlistQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관심 매물 {@code /api/me/wishlist} — API 명세서(매물) 1.9. 인증 「필수」는 보안 설정의 기본 규칙(인증 요구)이 건다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/wishlist")
public class WishlistController {

    private final WishlistQueryService queryService;
    private final WishlistCommandService commandService;

    @GetMapping
    public ApiResponse<CursorPage<WishlistResponse>> list(
            @AuthenticationPrincipal Long userId,
            @Valid @ModelAttribute WishlistListRequest request) {
        return ApiResponse.ok(queryService.findByUser(userId, request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> add(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody WishlistAddRequest request) {
        commandService.add(userId, request.propertyId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{propertyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ApiResponse<Void> remove(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long propertyId) {
        commandService.remove(userId, propertyId);
        return ApiResponse.ok();
    }
}
