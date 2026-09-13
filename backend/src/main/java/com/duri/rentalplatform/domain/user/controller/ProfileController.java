package com.duri.rentalplatform.domain.user.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.user.dto.request.ProfileUpdateRequest;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import com.duri.rentalplatform.domain.user.service.UserQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 경로 {@code /api/me/profile} 을 담당하는 컨트롤러. 인증 필수이며 본인 자원만 다룬다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/profile")
public class ProfileController {

    private final UserQueryService queryService;
    private final UserCommandService commandService;

    @GetMapping
    public ApiResponse<ProfileResponse> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(queryService.getProfile(userId));
    }

    @PutMapping
    public ApiResponse<ProfileResponse> update(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ProfileUpdateRequest request) {
        commandService.updateProfile(userId, request);
        // 수정 트랜잭션이 끝난 뒤 조회한다. 같은 트랜잭션에서 JPA 로 바꾼 값을 매퍼로 읽으면 반영 전 값을 읽는다.
        return ApiResponse.ok(queryService.getProfile(userId));
    }
}
