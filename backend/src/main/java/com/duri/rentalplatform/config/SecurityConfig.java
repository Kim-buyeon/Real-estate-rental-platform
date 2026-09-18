package com.duri.rentalplatform.config;

import com.duri.rentalplatform.common.security.JwtAccessDeniedHandler;
import com.duri.rentalplatform.common.security.JwtAuthenticationEntryPoint;
import com.duri.rentalplatform.common.security.JwtAuthenticationFilter;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토큰 기반 인증의 필터체인과 비밀번호 인코더.
 *
 * <p>경로 인가는 최소 규칙만 둔다. 인증 엔드포인트와 헬스 체크를 열고, 그중 명세가 토큰을 요구하는 경로만
 * 되잠근 뒤 나머지는 인증을 요구한다. 아직 읽지 않은 영역 명세의 경로를 여기서 추측해 두면 공개여야 할 API가
 * 잠기거나 보호해야 할 API가 열린다. 각 기능 슬라이스가 자기 경로 규칙을 더한다 — 인증 구분은 API 명세서
 * 공통 규약 1.5.
 *
 * <p>규칙은 좁은 것부터 적는다. 인가는 먼저 일치하는 규칙 하나로 끝나므로, 넓은 규칙이 앞에 오면 뒤의
 * 좁은 규칙은 평가되지 않는다. 슬라이스가 규칙을 더할 때도 이 순서를 지킨다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 공개 접두 아래에 있으면서도 유효한 액세스 토큰을 요구하는 경로.
     *
     * <p>로그아웃은 폐기할 리프레시 토큰의 주인을 액세스 토큰에서 얻는다. 열어 두면 주인을 모른 채 호출이
     * 성립해 아무것도 폐기하지 않고 성공 응답이 나간다 — API 명세서 회원·인증 1장에서 인증 엔드포인트 중
     * 로그아웃만 「필수」인 이유다.
     *
     * <p>메서드를 함께 맞추지 않고 경로만 맞춘다. 이 경로에는 로그아웃 외의 조작이 없으므로, 어떤 메서드로
     * 들어오든 신원을 먼저 확인하는 편이 빈틈이 없다. 메서드까지 맞추면 나머지 메서드가 공개 규칙으로 떨어져
     * 토큰 없이도 경로의 존재가 응답으로 드러난다.
     */
    private static final String[] TOKEN_REQUIRED_AUTH_PATHS = {"/api/auth/logout"};

    /** 인증 없이 호출할 수 있는 경로. 인증 엔드포인트는 토큰을 받기 전에 호출되므로 열어 둔다. */
    private static final String[] PUBLIC_PATHS = {
        "/api/auth/**", "/actuator/health", "/actuator/health/**"
    };

    /**
     * 인증 「선택」 조회 경로(API 명세서 매물 1장). 토큰 없이 통과하되, 토큰이 있으면 필터가 인증 사용자를
     * 채워 개인화 필드(관심 등록 여부)에 쓰인다. GET 만 연다 — 같은 접두의 다른 메서드는 기본 규칙을 따른다.
     */
    private static final String[] OPTIONAL_AUTH_GET_PATHS = {"/api/properties", "/api/properties/**"};

    /** 인증 「관리자」 경로(API 명세서 관리자 1장). 토큰의 role 이 ADMIN 이어야 한다. 아니면 필터 단계에서 403. */
    private static final String[] ADMIN_PATHS = {"/api/admin/**"};

    private final JwtTokenProvider jwtTokenProvider;
    private final JsonMapper jsonMapper;

    /** 실시간 수신 경로의 일회용 티켓을 소비한다 — API 명세서(알림) 1.1. 필터가 빈이 아니라 여기서 넘긴다. */
    private final StreamTicketStore streamTicketStore;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 토큰 인증이라 브라우저가 자동으로 보내는 자격 증명이 없다. CSRF 토큰이 막을 대상이 없다.
                .csrf(AbstractHttpConfigurer::disable)
                // 앱은 두 프로세스로 뜬다. 서버 메모리에 세션을 두면 인스턴스마다 인증 상태가 갈린다.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 비동기 디스패치는 이미 인가를 통과한 요청의 뒷마무리다(실시간 수신 스트림의 완료 · 타임아웃 · 오류).
                        // 무상태라 인증이 저장되지 않아 이 디스패치에는 인증이 비어 있고, 막으면 이미 커밋된 이벤트 스트림에
                        // 401 을 쓰려 한다. 첫 요청(REQUEST 디스패치)은 아래 규칙을 그대로 따른다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 순서를 바꾸지 않는다. 아래 PUBLIC_PATHS의 /api/auth/**가 로그아웃에도 일치하므로,
                        // 그것이 먼저 오면 이 규칙은 평가되지 않고 로그아웃이 조용히 열린다.
                        .requestMatchers(TOKEN_REQUIRED_AUTH_PATHS).authenticated()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, OPTIONAL_AUTH_GET_PATHS).permitAll()
                        .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(jsonMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(jsonMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, streamTicketStore),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
