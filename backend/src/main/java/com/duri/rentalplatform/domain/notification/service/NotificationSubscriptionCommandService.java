package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest;
import com.duri.rentalplatform.domain.notification.entity.NotificationSubscription;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.notification.repository.NotificationSubscriptionRepository;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.SeoulDistrict;
import com.duri.rentalplatform.domain.property.repository.WishlistRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 구독 설정 수정 — API 명세서(알림) 1.2.
 *
 * <p>한 요청이 한 트랜잭션이다. 사용자 행을 전부 지우고 요청대로 넣는다 — 명세가 전체를 전달하고 행 수가 사용자당 최대
 * 28 이라 차이 계산을 두지 않는다. 관심 매물 모니터링 수신 여부는 사용자의 관심 매물 {@code monitoring_yn} 에 일괄로
 * 반영한다 — 기능 정의서 NOTI-01 「켜면 등록된 관심 매물 전체가 대상」.
 *
 * <p>{@code enabled} 가 false 여도 온 조건은 비활성 행으로 남긴다. 버리면 다시 켤 때 화면이 조건을 잃는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSubscriptionCommandService {

    static final String DISTRICTS = "newProperty.conditions.districts";
    static final String CONDITIONS = "newProperty.conditions";

    private static final Set<String> SEOUL_DISTRICT_NAMES = Arrays.stream(SeoulDistrict.values())
            .map(SeoulDistrict::getDistrictName)
            .collect(Collectors.toUnmodifiableSet());

    private final NotificationSubscriptionRepository subscriptionRepository;
    private final WishlistRepository wishlistRepository;

    /**
     * @throws BusinessException {@link ErrorCode#INVALID_REQUEST} — 신규 매물을 켰는데 조건 · 자치구가 없음, 서울 자치구가
     *                           아닌 이름, 중복 자치구({@code field} = 위반 위치)
     */
    @Transactional
    public void replace(Long userId, NotificationSubscriptionUpdateRequest request) {
        List<NotificationSubscription> rows = new ArrayList<>(newPropertyRows(userId, request.newProperty()));
        rows.add(NotificationSubscription.toggle(userId, SubscriptionType.RATE_CHANGE,
                request.rateChange().enabled()));
        boolean monitoring = request.wishlistMonitoring().enabled();
        rows.add(NotificationSubscription.toggle(userId, SubscriptionType.WISHLIST_MONITORING, monitoring));
        rows.add(NotificationSubscription.toggle(userId, SubscriptionType.CONSULT_SCHEDULE,
                request.consultSchedule().enabled()));

        subscriptionRepository.deleteAllByUserIdInBulk(userId);
        subscriptionRepository.saveAll(rows);
        wishlistRepository.updateMonitoringByUserId(userId, monitoring);
    }

    /** 검증을 통과한 신규 매물 행. 자치구가 없으면(끈 설정만 가능) 자치구 null 인 한 행으로 조건을 남긴다. */
    private static List<NotificationSubscription> newPropertyRows(Long userId,
            NotificationSubscriptionUpdateRequest.NewProperty newProperty) {
        boolean enabled = newProperty.enabled();
        NotificationSubscriptionUpdateRequest.Conditions conditions = newProperty.conditions();
        if (conditions == null) {
            if (enabled) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, CONDITIONS);
            }
            return List.of(NotificationSubscription.newProperty(userId, null, null, null, false));
        }

        List<String> districts = conditions.districts() == null ? List.of() : conditions.districts();
        validateDistricts(districts, enabled);
        ContractType contractType = conditions.contractType();
        Long depositMax = conditions.depositMax();
        if (districts.isEmpty()) {
            return List.of(NotificationSubscription.newProperty(userId, null, contractType, depositMax, false));
        }
        return districts.stream()
                .map(district -> NotificationSubscription.newProperty(userId, district, contractType, depositMax,
                        enabled))
                .toList();
    }

    /**
     * 켰으면 1개 이상. 켜고 끔과 무관하게 서울 자치구명 · 중복 없음 — 끈 설정도 행으로 저장하므로 유일 인덱스에 걸리지
     * 않아야 한다. 25개 상한은 따로 세지 않는다. 25개 이름 안에서 중복이 없으면 넘을 수 없다.
     */
    private static void validateDistricts(List<String> districts, boolean enabled) {
        if (enabled && districts.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, DISTRICTS);
        }
        Set<String> seen = new HashSet<>();
        for (String district : districts) {
            if (district == null || !SEOUL_DISTRICT_NAMES.contains(district) || !seen.add(district)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, DISTRICTS);
            }
        }
    }
}
