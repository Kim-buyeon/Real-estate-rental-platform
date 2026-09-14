package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.dto.condition.NotificationListCondition;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationListRequest;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationPageResponse;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationResponse;
import com.duri.rentalplatform.domain.notification.mapper.NotificationMapper;
import com.duri.rentalplatform.domain.notification.vo.NotificationRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 알림 목록 — API 명세서(알림) 1.3. 목록 조회가 알림의 완전한 확인 수단이다(아키텍처 설계서(알림 전달) 1.1). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    /** 커서를 만든 정렬. 다른 목록의 커서를 거부하는 서명이다. 식별자만으로 넘기므로 정렬 값은 없다. */
    static final String SORT = "notificationId:desc";

    private final NotificationMapper notificationMapper;

    public NotificationPageResponse findByUser(Long userId, NotificationListRequest request) {
        Long lastNotificationId = null;
        if (request.cursor() != null && !request.cursor().isBlank()) {
            CursorCodec.Cursor cursor = CursorCodec.decode(request.cursor());
            if (!SORT.equals(cursor.s())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            lastNotificationId = cursor.id();
        }

        int size = request.size();
        List<NotificationRow> rows = notificationMapper.selectNotifications(
                new NotificationListCondition(userId, lastNotificationId, size + 1));
        boolean hasNext = rows.size() > size;
        List<NotificationRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext
                ? CursorCodec.encode(SORT, null, page.get(page.size() - 1).notificationId())
                : null;
        return new NotificationPageResponse(page.stream().map(NotificationResponse::of).toList(), nextCursor, hasNext,
                notificationMapper.countUnread(userId));
    }
}
