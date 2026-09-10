package com.duri.rentalplatform.common.security;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider.TokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization 헤더의 Bearer 토큰을 검증해 SecurityContext를 채운다.
 *
 * <p>토큰이 없거나 무효여도 여기서 응답을 쓰지 않는다. 사유만 요청 속성에 남기고 체인을 이어가며, 차단 여부는
 * 인가 규칙이 정한다. 그래야 공개 경로(재발급 등)를 만료된 토큰을 든 채 호출해도 막히지 않고, 보호 경로에서는
 * {@link JwtAuthenticationEntryPoint}가 남겨진 사유로 정확한 오류 코드를 응답할 수 있다.
 *
 * <p>스프링 빈으로 등록하지 않는다. {@code Filter} 빈은 시큐리티 체인과 별개로 서블릿 컨테이너에도 자동
 * 등록되어 모든 요청에서 두 번 실행된다. {@code SecurityConfig}가 직접 생성해 체인에만 넣는다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 토큰 검증 실패 사유를 담는 요청 속성 이름. 값은 {@link ErrorCode}다. */
    public static final String AUTHENTICATION_ERROR_ATTRIBUTE =
            JwtAuthenticationFilter.class.getName() + ".errorCode";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            TokenClaims claims = jwtTokenProvider.parseAccessToken(token);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(toAuthentication(claims));
            SecurityContextHolder.setContext(context);
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTHENTICATION_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }

    private Authentication toAuthentication(TokenClaims claims) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(ROLE_PREFIX + claims.role());
        return UsernamePasswordAuthenticationToken.authenticated(
                claims.userId(), null, List.of(authority));
    }
}
