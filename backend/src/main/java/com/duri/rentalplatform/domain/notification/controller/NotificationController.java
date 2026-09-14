package com.duri.rentalplatform.domain.notification.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationListRequest;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationPageResponse;
import com.duri.rentalplatform.domain.notification.service.NotificationQueryService;
import com.duri.rentalplatform.domain.notification.service.NotificationReadCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 목록 · 읽음 {@code /api/notifications} — API 명세서(알림) 1장 · 1.3. 인증 「필수」는 보안 설정의 기본 규칙(인증 요구)이 건다.
 * 읽음 처리는 data 를 null 로 돌려준다(명세 1.4 끝 문장).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationQueryService queryService;
    private final NotificationReadCommandService readCommandService;

    @GetMapping
    public ApiResponse<NotificationPageResponse> list(
            @AuthenticationPrincipal Long userId,
            @Valid @ModelAttribute NotificationListRequest request) {
        return ApiResponse.ok(queryService.findByUser(userId, request));
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long notificationId) {
        readCommandService.markRead(userId, notificationId);
        return ApiResponse.ok();
    }

    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllRead(@AuthenticationPrincipal Long userId) {
        readCommandService.markAllRead(userId);
        return ApiResponse.ok();
    }
}
