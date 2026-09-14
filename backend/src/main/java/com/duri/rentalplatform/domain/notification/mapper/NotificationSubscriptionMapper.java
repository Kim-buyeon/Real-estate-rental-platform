package com.duri.rentalplatform.domain.notification.mapper;

import com.duri.rentalplatform.domain.notification.vo.NotificationSubscriptionRow;
import java.util.List;

/** 알림 구독 설정 조회. XML 은 {@code resources/mapper/notification/NotificationSubscriptionMapper.xml}. */
public interface NotificationSubscriptionMapper {

    /** 사용자의 구독 행 전체(최대 28). {@code subscription_id} 오름차순 — 자치구가 저장한 순서로 돌아온다. */
    List<NotificationSubscriptionRow> selectByUser(Long userId);
}
