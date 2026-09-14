package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorCodec;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.dto.condition.WishlistCondition;
import com.duri.rentalplatform.domain.property.dto.request.WishlistListRequest;
import com.duri.rentalplatform.domain.property.dto.response.WishlistResponse;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WishlistQueryService} — 커서 해석 · +1 조회 · 다음 커서 · 응답 변환. */
class WishlistQueryServiceTest {

    private static final long USER_ID = 42L;
    private static final OffsetDateTime UTC = OffsetDateTime.of(2026, 7, 25, 2, 20, 0, 0, ZoneOffset.UTC);

    private WishlistMapper mapper;
    private WishlistQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WishlistMapper.class);
        service = new WishlistQueryService(mapper);
    }

    @Test
    @DisplayName("첫 페이지: 식별자 없이 size + 1 로 조회하고, 넘치면 잘라 마지막 행의 wishId 로 다음 커서를 만든다")
    void firstPageWithNext() {
        when(mapper.selectWishlist(new WishlistCondition(USER_ID, null, 3))).thenReturn(rows(30, 20, 10));

        CursorPage<WishlistResponse> page = service.findByUser(USER_ID, new WishlistListRequest(null, 2));

        assertThat(page.items()).extracting(WishlistResponse::propertyId).containsExactly(1030L, 1020L);
        assertThat(page.hasNext()).isTrue();
        CursorCodec.Cursor next = CursorCodec.decode(page.nextCursor());
        assertThat(next.s()).isEqualTo(WishlistQueryService.SORT);
        assertThat(next.id()).isEqualTo(20L);
    }

    @Test
    @DisplayName("다음 페이지: 커서의 식별자를 조건으로 넘기고, 넘치지 않으면 hasNext false · 커서 없음")
    void lastPage() {
        String cursor = CursorCodec.encode(WishlistQueryService.SORT, null, 20L);
        when(mapper.selectWishlist(new WishlistCondition(USER_ID, 20L, 3))).thenReturn(rows(10));

        CursorPage<WishlistResponse> page = service.findByUser(USER_ID, new WishlistListRequest(cursor, 2));

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("size 를 생략하면 기본 20 으로 21 건을 조회한다")
    void defaultSize() {
        when(mapper.selectWishlist(any())).thenReturn(List.of());

        CursorPage<WishlistResponse> page = service.findByUser(USER_ID, new WishlistListRequest(null, null));

        verify(mapper).selectWishlist(new WishlistCondition(USER_ID, null, 21));
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("다른 목록의 커서는 INVALID_REQUEST")
    void foreignCursorRejected() {
        String cursor = CursorCodec.encode("changedAt:desc", "2026-07-29T11:00", 20L);

        assertThatThrownBy(() -> service.findByUser(USER_ID, new WishlistListRequest(cursor, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("응답은 명세 필드를 옮기고 등록 시각을 서울 오프셋으로, 미분석 등급은 null 로 둔다")
    void mapsRow() {
        when(mapper.selectWishlist(any())).thenReturn(List.of(
                new WishlistRow(1L, 1024L, "강서구", 230_000_000L, RiskGrade.SAFE, RiskGrade.CAUTION, UTC),
                new WishlistRow(2L, 1025L, "강서구", 1L, null, null, UTC)));

        List<WishlistResponse> items = service.findByUser(USER_ID, new WishlistListRequest(null, 20)).items();

        assertThat(items.get(0)).isEqualTo(new WishlistResponse(1024L, "강서구", 230_000_000L, RiskGrade.SAFE,
                RiskGrade.CAUTION, OffsetDateTime.of(2026, 7, 25, 11, 20, 0, 0, ZoneOffset.ofHours(9))));
        assertThat(items.get(0).addedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(items.get(1).riskGrade()).isNull();
        assertThat(items.get(1).previousGrade()).isNull();
    }

    private static List<WishlistRow> rows(long... wishIds) {
        return LongStream.of(wishIds)
                .mapToObj(id -> new WishlistRow(id, 1000L + id, "강서구", 1L, RiskGrade.SAFE, null, UTC))
                .toList();
    }
}
