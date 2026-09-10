package com.duri.rentalplatform.common.security;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증은 되었으나 권한이 모자란 요청의 403 응답.
 *
 * <p>{@link JwtAuthenticationEntryPoint}와 마찬가지로 컨트롤러 밖에서 발생해 전역 예외 처리기가 잡지 못하므로
 * 공통 응답 봉투(API 명세서 공통 규약 1.2)를 여기서 직접 쓴다.
 */
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        ErrorCode errorCode = ErrorCode.AUTH_FORBIDDEN;

        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode, null));
    }
}
