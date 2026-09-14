package com.duri.rentalplatform.domain.notification.mapper;

import com.duri.rentalplatform.domain.notification.dto.condition.NotificationListCondition;
import com.duri.rentalplatform.domain.notification.vo.NotificationRow;
import java.util.List;

/** 알림 목록 조회 — 아키텍처 설계서(영속성 구조) 1.1. XML 은 {@code resources/mapper/notification/NotificationMapper.xml}. */
public interface NotificationMapper {

    /** 사용자의 알림 한 페이지(요청 크기 + 1). {@code notif_id DESC}. */
    List<NotificationRow> selectNotifications(NotificationListCondition condition);

    /** 사용자의 읽지 않은 알림 전체 수. 페이지와 무관하다. */
    long countUnread(Long userId);
}
