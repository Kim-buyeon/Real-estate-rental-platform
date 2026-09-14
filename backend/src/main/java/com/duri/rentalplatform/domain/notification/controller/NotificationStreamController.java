package com.duri.rentalplatform.domain.notification.controller;

import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 알림 수신 {@code GET /api/notifications/stream} — API 명세서(알림) 1.1 · 1.4. 인증 「필수」.
 *
 * <p><b>봉투로 감싸지 않는다</b> — 응답이 JSON 한 건이 아니라 끝나지 않는 이벤트 스트림이다. 연결 수명 · 하트비트 · 이벤트 형식은
 * 보관소와 구독자가 맡고 여기서는 연결을 열어 넘기기만 한다.
 *
 * <p>표준 {@code EventSource} 는 헤더를 붙일 수 없어 이 경로에 한해 쿼리 파라미터 토큰도 받는다 — 인증 필터가 읽는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private final SseEmitterStore sseEmitterStore;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Long userId) {
        return sseEmitterStore.connect(userId);
    }
}
