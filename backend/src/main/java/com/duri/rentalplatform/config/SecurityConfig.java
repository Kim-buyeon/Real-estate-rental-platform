package com.duri.rentalplatform.config;

import com.duri.rentalplatform.common.security.JwtAccessDeniedHandler;
import com.duri.rentalplatform.common.security.JwtAuthenticationEntryPoint;
import com.duri.rentalplatform.common.security.JwtAuthenticationFilter;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>경로 인가는 최소 규칙만 둔다. 인증 엔드포인트와 헬스 체크만 열고 나머지는 인증을 요구한다. 아직 읽지 않은
 * 영역 명세의 경로를 여기서 추측해 두면 공개여야 할 API가 잠기거나 보호해야 할 API가 열린다. 각 기능 슬라이스가
 * 자기 경로 규칙을 더한다 — 인증 구분은 API 명세서 공통 규약 1.5.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 인증 없이 호출할 수 있는 경로. 인증 엔드포인트는 토큰을 받기 전에 호출되므로 열어 둔다. */
    private static final String[] PUBLIC_PATHS = {
        "/api/auth/**", "/actuator/health", "/actuator/health/**"
    };

    private final JwtTokenProvider jwtTokenProvider;
    private final JsonMapper jsonMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 토큰 인증이라 브라우저가 자동으로 보내는 자격 증명이 없다. CSRF 토큰이 막을 대상이 없다.
                .csrf(AbstractHttpConfigurer::disable)
                // 앱은 두 프로세스로 뜬다. 서버 메모리에 세션을 두면 인스턴스마다 인증 상태가 갈린다.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(jsonMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(jsonMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
