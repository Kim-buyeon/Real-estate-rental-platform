package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.condition.WishlistedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.WishlistMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** {@link WishlistedPropertyIdReader} — 식별자 커서 페이지를 이어 읽고, 끝을 null 로 알린다. */
class WishlistedPropertyIdReaderTest {

    private static final int PAGE_SIZE = 2;

    private WishlistMapper wishlistMapper;
    private WishlistedPropertyIdReader reader;

    @BeforeEach
    void setUp() {
        wishlistMapper = mock(WishlistMapper.class);
        reader = new WishlistedPropertyIdReader(wishlistMapper, PAGE_SIZE);
    }

    @Test
    @DisplayName("마지막 식별자를 다음 커서로 이어 읽고, 페이지 크기보다 적게 오면 더 조회하지 않고 끝낸다")
    void readsPagesByCursorAndStopsAfterPartialPage() {
        page(null, 1L, 2L);
        page(2L, 3L, 4L);
        page(4L, 5L);

        assertThat(readAll()).containsExactly(1L, 2L, 3L, 4L, 5L);

        InOrder order = inOrder(wishlistMapper);
        order.verify(wishlistMapper).selectWishlistedPropertyIds(new WishlistedPropertyCondition(null, PAGE_SIZE));
        order.verify(wishlistMapper).selectWishlistedPropertyIds(new WishlistedPropertyCondition(2L, PAGE_SIZE));
        order.verify(wishlistMapper).selectWishlistedPropertyIds(new WishlistedPropertyCondition(4L, PAGE_SIZE));
        verify(wishlistMapper, times(3)).selectWishlistedPropertyIds(any());
    }

    @Test
    @DisplayName("마지막 페이지가 꽉 차면 빈 페이지를 한 번 더 읽고 끝낸다")
    void fullLastPageReadsOneEmptyPage() {
        page(null, 1L, 2L);
        page(2L);

        assertThat(readAll()).containsExactly(1L, 2L);
        verify(wishlistMapper, times(2)).selectWishlistedPropertyIds(any());
    }

    @Test
    @DisplayName("대상이 없으면 첫 읽기가 null 이고, 끝난 뒤 다시 읽어도 조회하지 않는다")
    void noTargets() {
        page(null);

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        verify(wishlistMapper, times(1)).selectWishlistedPropertyIds(any());
    }

    @Test
    @DisplayName("페이지 크기가 1 미만이면 만들지 않는다 — 0 이면 첫 페이지부터 끝으로 보여 대상이 있어도 아무것도 읽지 않는다")
    void rejectsNonPositivePageSize() {
        assertThatThrownBy(() -> new WishlistedPropertyIdReader(wishlistMapper, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<Long> readAll() {
        List<Long> read = new ArrayList<>();
        for (Long id = reader.read(); id != null; id = reader.read()) {
            read.add(id);
        }
        return read;
    }

    private void page(Long lastPropertyId, Long... propertyIds) {
        when(wishlistMapper.selectWishlistedPropertyIds(new WishlistedPropertyCondition(lastPropertyId, PAGE_SIZE)))
                .thenReturn(List.of(propertyIds));
    }
}
