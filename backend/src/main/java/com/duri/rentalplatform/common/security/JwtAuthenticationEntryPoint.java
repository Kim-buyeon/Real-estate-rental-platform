package com.duri.rentalplatform.common.security;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증되지 않은 요청이 보호 자원에 닿았을 때의 401 응답.
 *
 * <p>스프링 기본 401 본문은 공통 응답 봉투(API 명세서 공통 규약 1.2)를 지키지 않는다. 필터·컨트롤러 밖에서
 * 발생하므로 {@code GlobalExceptionHandler}도 잡지 못한다. 그래서 여기서 직접 봉투로 쓴다.
 *
 * <p>오류 코드는 {@link JwtAuthenticationFilter}가 남긴 사유를 따른다. 만료면 재발급을 유도해야 하므로
 * 무효 토큰과 구분한다. 사유가 없으면(헤더 자체가 없는 경우) 인증 정보 불일치로 본다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        ErrorCode errorCode = resolveErrorCode(request);

        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode, null));
    }

    private ErrorCode resolveErrorCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTHENTICATION_ERROR_ATTRIBUTE);
        return attribute instanceof ErrorCode errorCode ? errorCode : ErrorCode.AUTH_INVALID_CREDENTIAL;
    }
}
