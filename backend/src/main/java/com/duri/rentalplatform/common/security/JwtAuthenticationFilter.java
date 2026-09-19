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
 * Authorization 헤더의 Bearer 토큰을 검증해 SecurityContext를 채운다. 실시간 알림 수신 경로만 쿼리 파라미터 일회용 티켓도 받는다.
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

    /**
     * 인증에 쓴 액세스 토큰의 만료 시각을 담는 요청 속성 이름. 값은 {@link java.time.Instant}다. 실시간 수신 연결 수명에 쓴다.
     * 헤더 토큰은 토큰에서, 티켓은 티켓에 담긴 값에서 채운다 — 두 경로의 연결 수명이 같아야 한다.
     */
    public static final String ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE =
            "com.duri.rentalplatform.common.security.JwtAuthenticationFilter.accessTokenExpiresAt";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    /** 쿼리 파라미터 자격 증명을 받는 유일한 경로 — 실시간 알림 수신. */
    static final String STREAM_PATH = "/api/notifications/stream";

    /**
     * 실시간 수신 경로의 일회용 티켓 파라미터. <b>액세스 토큰을 쿼리로 받지 않는다</b> — RFC 9700 이 URI 쿼리의 액세스 토큰을
     * 금지한다. 티켓은 한 번 쓰이면 사라지고 수초 만에 만료되므로, 접근 로그 · 브라우저 기록에 남아도 열 수 있는 것이 없다.
     */
    static final String TICKET_PARAMETER = "ticket";

    private final JwtTokenProvider jwtTokenProvider;
    private final StreamTicketStore streamTicketStore;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null) {
            authenticate(request, token);
        } else if (isStreamRequest(request)) {
            authenticateWithTicket(request);
        }
        filterChain.doFilter(request, response);
    }

    /** 헤더의 Bearer 토큰. 경로를 가리지 않는다. */
    static String resolveToken(HttpServletRequest request) {
        return bearerToken(request.getHeader(AUTHORIZATION_HEADER));
    }

    /**
     * 실시간 수신 경로의 GET 에서만 쿼리 파라미터 {@value #TICKET_PARAMETER} 를 읽는다 — 표준 {@code EventSource} 는 헤더를
     * 붙일 수 없다(API 명세서 알림 1.1). 다른 경로는 읽지 않는다. 헤더 토큰이 있으면 그것을 쓰므로 여기까지 오지 않는다.
     */
    static String resolveTicket(HttpServletRequest request) {
        if (!isStreamRequest(request)) {
            return null;
        }
        String parameter = request.getParameter(TICKET_PARAMETER);
        if (parameter == null) {
            return null;
        }
        String ticket = parameter.trim();
        return ticket.isEmpty() ? null : ticket;
    }

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 컨텍스트 경로 아래로 배포되어도 같은 경로로 맞춘다. */
    private static boolean isStreamRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return STREAM_PATH.equals(path);
    }

    /**
     * 티켓을 소비해 인증을 세운다. 소비는 한 번만 성공한다 — 로그에 남은 티켓으로 두 번째 연결을 열 수 없다.
     *
     * <p>티켓이 없으면 아무것도 하지 않는다. 사유를 남기지 않아도 인가 규칙이 막고 {@link JwtAuthenticationEntryPoint} 가
     * 기본 사유로 401 봉투를 쓴다 — 스트림을 열기 전이다(API 명세서 알림 1.1).
     *
     * <p>권한을 담지 않는다. 티켓은 수신 연결 하나를 여는 자격일 뿐이고 그 경로는 인증 「필수」 외의 권한을 요구하지 않는다.
     * 역할을 실어 두면 티켓이 액세스 토큰을 대신하게 된다.
     *
     * <p>다만 <b>발급에 쓴 액세스 토큰의 만료 시각은 헤더 토큰 경로와 같은 요청 속성에 넣는다.</b> 연결 수명이 두 경로에서 같아야
     * 한다 — 그러지 않으면 만료 직전 토큰으로 받은 티켓이 세션보다 오래 사는 연결을 연다.
     */
    private void authenticateWithTicket(HttpServletRequest request) {
        String ticket = resolveTicket(request);
        if (ticket == null) {
            return;
        }
        streamTicketStore.consume(ticket).ifPresentOrElse(
                claims -> {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            claims.userId(), null, List.of()));
                    if (claims.tokenExpiresAt() != null) {
                        request.setAttribute(
                                ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE, claims.tokenExpiresAt());
                    }
                    SecurityContextHolder.setContext(context);
                },
                () -> {
                    SecurityContextHolder.clearContext();
                    request.setAttribute(
                            AUTHENTICATION_ERROR_ATTRIBUTE, ErrorCode.AUTH_INVALID_CREDENTIAL);
                });
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            TokenClaims claims = jwtTokenProvider.parseAccessToken(token);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(toAuthentication(claims));
            request.setAttribute(ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE, claims.expiresAt());
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
