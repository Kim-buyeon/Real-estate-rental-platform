package com.duri.rentalplatform.domain.user.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
