package com.duri.rentalplatform.domain.notification.controller;

import com.duri.rentalplatform.common.security.JwtAuthenticationFilter;
import com.duri.rentalplatform.domain.notification.service.NotificationStreamService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 알림 수신 {@code GET /api/notifications/stream} — API 명세서(알림) 1.1 · 1.4. 인증 「필수」.
 *
 * <p><b>봉투로 감싸지 않는다</b> — 응답이 JSON 한 건이 아니라 끝나지 않는 이벤트 스트림이다. 연결 수명은 서비스가 토큰 만료로
 * 정한다.
 *
 * <p>표준 {@code EventSource} 는 헤더를 붙일 수 없어 이 경로에 한해 쿼리 파라미터 토큰도 받는다 — 인증 필터가 읽는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private final NotificationStreamService streamService;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal Long userId,
            @RequestAttribute(name = JwtAuthenticationFilter.ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE, required = false)
            Instant tokenExpiresAt) {
        return streamService.connect(userId, tokenExpiresAt);
    }
}
