package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.dto.condition.NotificationListCondition;
import com.duri.rentalplatform.domain.notification.dto.request.NotificationListRequest;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationPageResponse;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.mapper.NotificationMapper;
import com.duri.rentalplatform.domain.notification.vo.NotificationRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NotificationQueryService} — 커서 페이지 · 다음 커서 · unreadCount · 응답 변환. */
class NotificationQueryServiceTest {

    private static final long USER_ID = 42L;

    private NotificationMapper mapper;
    private NotificationQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        service = new NotificationQueryService(mapper);
    }

    @Test
    @DisplayName("크기 + 1 건이 오면 다음 페이지가 있고 커서는 마지막 행 식별자, unreadCount 는 매퍼 값")
    void firstPageWithNext() {
        when(mapper.selectNotifications(any())).thenReturn(rows(30, 28, 27));
        when(mapper.countUnread(USER_ID)).thenReturn(7L);

        NotificationPageResponse page = service.findByUser(USER_ID, new NotificationListRequest(null, 2));

        assertThat(page.items()).extracting("notificationId").containsExactly(30L, 28L);
        assertThat(page.hasNext()).isTrue();
        assertThat(CursorCodec.decode(page.nextCursor()).id()).isEqualTo(28L);
        assertThat(page.unreadCount()).isEqualTo(7L);
        verify(mapper).selectNotifications(new NotificationListCondition(USER_ID, null, 3));
    }

    @Test
    @DisplayName("커서를 식별자로 풀어 넘기고, 모자라게 오면 마지막 페이지")
    void nextPageFromCursor() {
        String cursor = CursorCodec.encode(NotificationQueryService.SORT, null, 28L);
        when(mapper.selectNotifications(any())).thenReturn(rows(27));

        NotificationPageResponse page = service.findByUser(USER_ID, new NotificationListRequest(cursor, 2));

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(mapper).selectNotifications(new NotificationListCondition(USER_ID, 28L, 3));
    }

    @Test
    @DisplayName("크기를 생략하면 기본 20")
    void defaultSize() {
        when(mapper.selectNotifications(any())).thenReturn(rows(LongStream.rangeClosed(1, 5).toArray()));

        service.findByUser(USER_ID, new NotificationListRequest(null, null));

        verify(mapper).selectNotifications(new NotificationListCondition(USER_ID, null, 21));
    }

    @Test
    @DisplayName("다른 목록의 커서는 400 INVALID_REQUEST")
    void rejectsForeignCursor() {
        String foreign = CursorCodec.encode("wishId:desc", null, 5L);

        assertThatThrownBy(() -> service.findByUser(USER_ID, new NotificationListRequest(foreign, 20)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("제목은 유형 문구, 시각은 서울 오프셋으로 바뀐다")
    void convertsTitleAndOffset() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 7, 28, 18, 5, 0, 0, ZoneOffset.UTC);
        when(mapper.selectNotifications(any())).thenReturn(List.of(new NotificationRow(
                9012L, NotificationType.REGISTRY_CHANGE, 1024L, "갑구 1 · 을구 1 · aaaaaaaa",
                "갑구 1 · 을구 2 · bbbbbbbb", true, utc)));

        NotificationPageResponse page = service.findByUser(USER_ID, new NotificationListRequest(null, 20));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("관심 매물의 등기에 변동이 생겼습니다");
            assertThat(item.isRead()).isTrue();
            assertThat(item.createdAt()).isEqualTo(OffsetDateTime.of(2026, 7, 29, 3, 5, 0, 0, ZoneOffset.ofHours(9)));
            assertThat(item.createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        });
    }

    private static List<NotificationRow> rows(long... ids) {
        OffsetDateTime at = OffsetDateTime.of(2026, 7, 29, 3, 5, 0, 0, ZoneOffset.ofHours(9));
        return LongStream.of(ids)
                .mapToObj(id -> new NotificationRow(id, NotificationType.RISK_CHANGE, 1024L, "CAUTION", "DANGER",
                        false, at))
                .toList();
    }
}
