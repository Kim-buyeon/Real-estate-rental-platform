package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.domain.notification.dto.response.NotificationSubscriptionResponse;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.notification.mapper.NotificationSubscriptionMapper;
import com.duri.rentalplatform.domain.notification.vo.NotificationSubscriptionRow;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 구독 설정 조회 — API 명세서(알림) 1.2.
 *
 * <p>행이 없는 유형은 기본값이다. 관심 매물 모니터링은 켬, 나머지는 끔 — 관심 매물 등록이 곧 모니터링 알림 범위다
 * (기능 정의서 PROP-05 · NOTI-01).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSubscriptionQueryService {

    /** 행이 없을 때 관심 매물 모니터링의 수신 여부. */
    static final boolean DEFAULT_WISHLIST_MONITORING = true;

    private final NotificationSubscriptionMapper mapper;

    public NotificationSubscriptionResponse get(Long userId) {
        List<NotificationSubscriptionRow> rows = mapper.selectByUser(userId);
        return new NotificationSubscriptionResponse(
                newProperty(rows),
                toggle(rows, SubscriptionType.RATE_CHANGE, false),
                toggle(rows, SubscriptionType.WISHLIST_MONITORING, DEFAULT_WISHLIST_MONITORING),
                toggle(rows, SubscriptionType.CONSULT_SCHEDULE, false));
    }

    /** 관심 매물 등록 시 {@code monitoring_yn} 에 넣을 값 — 사용자의 모니터링 설정. 행이 없으면 기본값. */
    public boolean isWishlistMonitoringEnabled(Long userId) {
        return toggle(mapper.selectByUser(userId), SubscriptionType.WISHLIST_MONITORING,
                DEFAULT_WISHLIST_MONITORING).enabled();
    }

    /**
     * 자치구 행들을 {@code districts[]} 로 묶는다. 활성 여부 · 계약 유형 · 상한은 한 요청이 모든 행에 같게 쓰므로 첫 행의
     * 것이다. 자치구 없이 끈 설정은 자치구가 null 인 한 행이라 배열에서 뺀다.
     */
    private static NotificationSubscriptionResponse.NewProperty newProperty(List<NotificationSubscriptionRow> rows) {
        List<NotificationSubscriptionRow> own = rows.stream()
                .filter(row -> row.subscriptionType() == SubscriptionType.NEW_PROPERTY)
                .toList();
        if (own.isEmpty()) {
            return new NotificationSubscriptionResponse.NewProperty(false,
                    new NotificationSubscriptionResponse.Conditions(List.of(), null, null));
        }
        NotificationSubscriptionRow first = own.getFirst();
        List<String> districts = own.stream()
                .map(NotificationSubscriptionRow::targetDistrict)
                .filter(Objects::nonNull)
                .toList();
        return new NotificationSubscriptionResponse.NewProperty(first.active(),
                new NotificationSubscriptionResponse.Conditions(districts, first.contractType(), first.depositMax()));
    }

    private static NotificationSubscriptionResponse.Toggle toggle(List<NotificationSubscriptionRow> rows,
            SubscriptionType type, boolean defaultEnabled) {
        return new NotificationSubscriptionResponse.Toggle(rows.stream()
                .filter(row -> row.subscriptionType() == type)
                .findFirst()
                .map(NotificationSubscriptionRow::active)
                .orElse(defaultEnabled));
    }
}
