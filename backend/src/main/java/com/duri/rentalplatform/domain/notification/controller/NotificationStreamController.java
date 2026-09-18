package com.duri.rentalplatform.domain.notification.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.security.JwtAuthenticationFilter;
import com.duri.rentalplatform.domain.notification.dto.response.StreamTicketResponse;
import com.duri.rentalplatform.domain.notification.service.NotificationStreamService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 알림 수신 {@code GET /api/notifications/stream} 과 그 연결용 티켓 발급
 * {@code POST /api/notifications/stream-ticket} — API 명세서(알림) 1.1 · 1.4. 둘 다 인증 「필수」이고, 보안 설정의 기본
 * 규칙(인증 요구)이 건다.
 *
 * <p><b>스트림 응답은 봉투로 감싸지 않는다</b> — JSON 한 건이 아니라 끝나지 않는 이벤트 스트림이다. 발급 응답은 일반 응답이므로
 * 봉투에 담는다.
 *
 * <p>표준 {@code EventSource} 는 헤더를 붙일 수 없어 이 경로에 한해 쿼리 파라미터 자격 증명을 받는다. 액세스 토큰이 아니라
 * <b>일회용 티켓</b>이다 — 인증 필터가 읽어 소비한다. 티켓은 발급 엔드포인트에서 액세스 토큰으로 받는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private final NotificationStreamService streamService;

    /**
     * 연결용 일회용 티켓 발급. 액세스 토큰으로 인증된 사용자만 부를 수 있다.
     *
     * <p>연결을 열 때마다 새로 받는다 — 티켓은 한 번 쓰이면 사라진다. 자원을 만드는 요청이 아니라 자격 증명을 내주는
     * 요청이므로 201 이 아니라 200 이다(공통 규약 1.3).
     *
     * <p>이 요청을 인증한 액세스 토큰의 만료 시각을 티켓에 담아 둔다 — 그래야 티켓으로 연 연결의 수명이 헤더 토큰으로 연
     * 연결과 같아진다. 값은 스트림과 같은 요청 속성에서 받는다.
     */
    @PostMapping("/stream-ticket")
    public ApiResponse<StreamTicketResponse> issueTicket(
            @AuthenticationPrincipal Long userId,
            @RequestAttribute(name = JwtAuthenticationFilter.ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE, required = false)
            Instant tokenExpiresAt) {
        return ApiResponse.ok(streamService.issueTicket(userId, tokenExpiresAt));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal Long userId,
            @RequestAttribute(name = JwtAuthenticationFilter.ACCESS_TOKEN_EXPIRES_AT_ATTRIBUTE, required = false)
            Instant tokenExpiresAt) {
        return streamService.connect(userId, tokenExpiresAt);
    }
}
