package com.duri.rentalplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link JwtAuthenticationFilter#resolveToken} — 헤더 우선, 쿼리 파라미터 토큰은 실시간 수신 경로의 GET 에서만(API 명세서 알림 1.1).
 */
class JwtAuthenticationFilterTokenResolutionTest {

    private static final String STREAM = "/api/notifications/stream";

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return request;
    }

    @Test
    @DisplayName("헤더의 Bearer 토큰을 쓴다")
    void usesBearerHeader() {
        MockHttpServletRequest request = request("GET", "/api/me/wishlist");
        request.addHeader("Authorization", "Bearer header-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isEqualTo("header-token");
    }

    @Test
    @DisplayName("스트림 경로에서 헤더가 없으면 쿼리 파라미터 accessToken 을 쓴다")
    void usesQueryParameterOnStreamPathWithoutHeader() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isEqualTo("query-token");
    }

    @Test
    @DisplayName("스트림 경로에서 헤더와 쿼리 파라미터가 둘 다 있으면 헤더를 쓴다")
    void headerWinsOverQueryParameter() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.addHeader("Authorization", "Bearer header-token");
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isEqualTo("header-token");
    }

    @Test
    @DisplayName("다른 경로의 쿼리 파라미터 accessToken 은 읽지 않는다")
    void ignoresQueryParameterOnOtherPaths() {
        MockHttpServletRequest request = request("GET", "/api/notifications");
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isNull();
    }

    @Test
    @DisplayName("스트림 경로라도 GET 이 아니면 쿼리 파라미터를 읽지 않는다")
    void ignoresQueryParameterForNonGet() {
        MockHttpServletRequest request = request("POST", STREAM);
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isNull();
    }

    @Test
    @DisplayName("컨텍스트 경로 아래로 배포되어도 스트림 경로를 알아본다")
    void recognizesStreamPathUnderContextPath() {
        MockHttpServletRequest request = request("GET", "/app" + STREAM);
        request.setContextPath("/app");
        request.setParameter("accessToken", "query-token");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isEqualTo("query-token");
    }

    @Test
    @DisplayName("빈 쿼리 파라미터는 토큰이 없는 것으로 본다")
    void blankQueryParameterIsNoToken() {
        MockHttpServletRequest request = request("GET", STREAM);
        request.setParameter("accessToken", "  ");

        assertThat(JwtAuthenticationFilter.resolveToken(request)).isNull();
    }
}
