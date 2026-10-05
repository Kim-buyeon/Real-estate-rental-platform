package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.condition.ReanalysisPendingPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PropertyRefreshTargetReader} — 최신 판정 없는 새 매물을 먼저, 시세 변경 매물을 뒤에, 둘 다 DB 에서 식별자 커서로 한 페이지씩
 * 내준다. 메모리 목록을 넘겨받지 않는다.
 */
class PropertyRefreshTargetReaderTest {

    private static final int PAGE_SIZE = 2;

    private PropertyMapper propertyMapper;

    @BeforeEach
    void setUp() {
        propertyMapper = mock(PropertyMapper.class);
    }

    @Test
    @DisplayName("미판정 매물을 페이지를 넘어 첫 판정 대상으로 먼저, 이어서 시세 변경 매물을 페이지를 넘어 재분석 대상으로 내준다")
    void unanalyzedFirstThenPriceChangedAcrossPages() {
        unanalyzedPage(null, 1L, 2L);
        unanalyzedPage(2L, 10L);
        priceChangedPage(null, 3L, 7L);
        priceChangedPage(7L, 9L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(
                PropertyRefreshTarget.unanalyzed(1L),
                PropertyRefreshTarget.unanalyzed(2L),
                PropertyRefreshTarget.unanalyzed(10L),
                PropertyRefreshTarget.reanalysisPending(3L),
                PropertyRefreshTarget.reanalysisPending(7L),
                PropertyRefreshTarget.reanalysisPending(9L));
    }

    @Test
    @DisplayName("한 갈래가 페이지 크기와 딱 맞게 끝나면 빈 페이지를 한 번 더 조회하고 다음 갈래로 넘어간다")
    void fullLastPageIsFollowedByEmptyPage() {
        unanalyzedPage(null, 3L, 7L);
        unanalyzedPage(7L);
        priceChangedPage(null, 1L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(
                PropertyRefreshTarget.unanalyzed(3L),
                PropertyRefreshTarget.unanalyzed(7L),
                PropertyRefreshTarget.reanalysisPending(1L));
        verify(propertyMapper, times(2)).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("페이지 크기보다 적게 오면 마지막 페이지로 보고 더 조회하지 않는다")
    void shortPageEndsReading() {
        unanalyzedPage(null);
        priceChangedPage(null, 1L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(PropertyRefreshTarget.reanalysisPending(1L));
        verify(propertyMapper, times(1)).selectReanalysisPendingPropertyIds(any());
        verify(propertyMapper, times(1)).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("끝난 뒤 다시 읽어도 조회하지 않고 null 이다")
    void readAfterEndDoesNotQueryAgain() {
        priceChangedPage(null);
        unanalyzedPage(null);
        PropertyRefreshTargetReader reader = reader();

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();

        verify(propertyMapper, times(1)).selectReanalysisPendingPropertyIds(any());
        verify(propertyMapper, times(1)).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("만들 때는 조회하지 않는다 — 읽기 단계가 적재 스텝보다 먼저 만들어진다")
    void doesNotQueryOnConstruction() {
        reader();

        verifyNoInteractions(propertyMapper);
    }

    @Test
    @DisplayName("미판정 매물을 다 내주기 전에는 시세 변경 조회를 하지 않는다")
    void priceChangedQueryWaitsForUnanalyzed() {
        unanalyzedPage(null, 5L);
        PropertyRefreshTargetReader reader = reader();

        assertThat(reader.read()).isEqualTo(PropertyRefreshTarget.unanalyzed(5L));

        verify(propertyMapper, never()).selectReanalysisPendingPropertyIds(any());
    }

    @Test
    @DisplayName("페이지 크기가 1 보다 작으면 만들 수 없다")
    void rejectsNonPositivePageSize() {
        assertThatThrownBy(() -> new PropertyRefreshTargetReader(propertyMapper, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PropertyRefreshTargetReader reader() {
        return new PropertyRefreshTargetReader(propertyMapper, PAGE_SIZE);
    }

    private void priceChangedPage(Long lastPropertyId, Long... propertyIds) {
        when(propertyMapper.selectReanalysisPendingPropertyIds(new ReanalysisPendingPropertyCondition(lastPropertyId, PAGE_SIZE)))
                .thenReturn(List.of(propertyIds));
    }

    private void unanalyzedPage(Long lastPropertyId, Long... propertyIds) {
        when(propertyMapper.selectUnanalyzedPropertyIds(new UnanalyzedPropertyCondition(lastPropertyId, PAGE_SIZE)))
                .thenReturn(List.of(propertyIds));
    }

    private static List<PropertyRefreshTarget> readAll(PropertyRefreshTargetReader reader) {
        List<PropertyRefreshTarget> targets = new ArrayList<>();
        for (PropertyRefreshTarget target = reader.read(); target != null; target = reader.read()) {
            targets.add(target);
        }
        return targets;
    }
}
