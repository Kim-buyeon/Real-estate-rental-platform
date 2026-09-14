package com.duri.rentalplatform.domain.notification.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationSubscriptionResponse;
import com.duri.rentalplatform.domain.notification.service.NotificationSubscriptionCommandService;
import com.duri.rentalplatform.domain.notification.service.NotificationSubscriptionQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 구독 설정 {@code /api/me/notification-subscriptions} — API 명세서(알림) 1.2. 인증 「필수」는 보안 설정의 기본
 * 규칙(인증 요구)이 건다.
 *
 * <p>수정은 트랜잭션이 끝난 뒤 조회 결과를 돌려준다. 같은 트랜잭션에서 JPA 로 바꾼 값을 매퍼로 읽지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/notification-subscriptions")
public class NotificationSubscriptionController {

    private final NotificationSubscriptionQueryService queryService;
    private final NotificationSubscriptionCommandService commandService;

    @GetMapping
    public ApiResponse<NotificationSubscriptionResponse> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(queryService.get(userId));
    }

    @PutMapping
    public ApiResponse<NotificationSubscriptionResponse> replace(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody NotificationSubscriptionUpdateRequest request) {
        commandService.replace(userId, request);
        return ApiResponse.ok(queryService.get(userId));
    }
}
