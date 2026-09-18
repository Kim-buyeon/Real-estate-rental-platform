package com.duri.rentalplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link JwtAuthenticationFilter} 의 자격 증명 추출 — 토큰은 헤더에서만, 티켓은 실시간 수신 경로의 GET 쿼리에서만(API 명세서 알림 1.1).
 *
 * <p>쿼리 파라미터 {@code accessToken} 은 더 이상 읽지 않는다 — URI 쿼리의 액세스 토큰은 RFC 9700 이 금지한다.
 */
class JwtAuthenticationFilterTokenResolutionTest {

    private static final String STREAM = "/api/notifications/stream";

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    @DisplayName("헤더의 Bearer 토큰을 쓴다")
    void usesBearerHeader() {
        MockHttpServletRequest request = request("GET", "/api/me/wishlist");
        request.addHeader("Authorization", "Bearer header-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isEqualTo("header-token");
    }

    @Test
    @DisplayName("스트림 경로에서도 토큰은 헤더에서만 읽는다 — 쿼리 파라미터 accessToken 은 인증이 아니다")
    void ignoresAccessTokenQueryParameterOnStreamPath() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isNull();
        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isNull();
    }

    @Test
    @DisplayName("스트림 경로의 쿼리 파라미터 ticket 을 읽는다")
    void readsTicketOnStreamPath() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.setParameter("ticket", "one-time-ticket");

        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isEqualTo("one-time-ticket");
    }

    @Test
    @DisplayName("다른 경로의 쿼리 파라미터 ticket 은 읽지 않는다")
    void ignoresTicketOnOtherPaths() {
        MockHttpServletRequest request = request("GET", "/api/notifications");
        request.setParameter("ticket", "one-time-ticket");

        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isNull();
    }

    @Test
    @DisplayName("스트림 경로라도 GET 이 아니면 티켓을 읽지 않는다")
    void ignoresTicketForNonGet() {
        MockHttpServletRequest request = request("POST", STREAM);
        request.setParameter("ticket", "one-time-ticket");

        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isNull();
    }

    @Test
    @DisplayName("컨텍스트 경로 아래로 배포되어도 스트림 경로를 알아본다")
    void recognizesStreamPathUnderContextPath() {
        MockHttpServletRequest request = request("GET", "/app" + STREAM);
        request.setContextPath("/app");
        request.setParameter("ticket", "one-time-ticket");

        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isEqualTo("one-time-ticket");
    }

    @Test
    @DisplayName("빈 티켓 파라미터는 티켓이 없는 것으로 본다")
    void blankTicketIsNoTicket() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.setParameter("ticket", "  ");

        assertThat(JwtAuthenticationFilter.resolveTicket(request)).isNull();
    }
}
