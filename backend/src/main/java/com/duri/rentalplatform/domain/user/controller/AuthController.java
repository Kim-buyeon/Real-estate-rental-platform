package com.duri.rentalplatform.domain.user.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.user.dto.request.LoginRequest;
import com.duri.rentalplatform.domain.user.dto.request.ReissueRequest;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.dto.response.TokenResponse;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 경로 {@code /api/auth} 하위를 담당하는 컨트롤러.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final UserCommandService userCommandService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> signUp(@Valid @RequestBody SignupRequest request) {
        userCommandService.signUpWithEmail(request);
        // data를 비우는 이유: API 명세서에 가입 응답 본문이 없어 명세에 없는 필드를 만들지 않는다.
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(userCommandService.login(request));
    }

    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ApiResponse.ok(userCommandService.reissue(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
        userCommandService.logout(userId);
        // 204가 아닌 이유: 명세는 로그아웃이 본문 없이 호출되고 성공 시 data를 null로 반환한다고 적었다.
        // 공통 봉투 {"success": true}는 나가므로 본문이 없는 응답이 아니다.
        // 삭제 완료에 쓰는 204를 여기에 붙이면 그 봉투가 잘려 나간다.
        return ApiResponse.ok();
    }
}
