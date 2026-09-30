package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.mapper.PropertyMapper;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PropertyRefreshTargetReader} — 시세 변경 매물을 먼저, 최신 판정 없는 매물을 식별자 커서로 뒤에 내주고, 두 갈래에 모두
 * 든 매물은 한 번만 내준다.
 */
class PropertyRefreshTargetReaderTest {

    private static final int PAGE_SIZE = 2;

    private PropertyMapper propertyMapper;
    private PropertyRefreshReport report;

    @BeforeEach
    void setUp() {
        propertyMapper = mock(PropertyMapper.class);
        report = new PropertyRefreshReport();
    }

    @Test
    @DisplayName("시세 변경 매물을 먼저 재분석 대상으로, 이어서 미판정 매물을 페이지를 넘어 첫 판정 대상으로 내준다")
    void priceChangedFirstThenUnanalyzedAcrossPages() {
        report.recordLoad(new PropertyRefreshResult(List.of(10L), List.of(7L, 3L)));
        page(null, 1L, 2L);
        page(2L, 10L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(
                PropertyRefreshTarget.priceChanged(7L),
                PropertyRefreshTarget.priceChanged(3L),
                PropertyRefreshTarget.unanalyzed(1L),
                PropertyRefreshTarget.unanalyzed(2L),
                PropertyRefreshTarget.unanalyzed(10L));
    }

    @Test
    @DisplayName("시세가 바뀌었는데 최신 판정도 없는 매물은 재분석 대상으로 한 번만 나온다")
    void priceChangedAndUnanalyzedAppearsOnce() {
        report.recordLoad(new PropertyRefreshResult(List.of(), List.of(2L)));
        page(null, 1L, 2L);
        page(2L, 3L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(
                PropertyRefreshTarget.priceChanged(2L),
                PropertyRefreshTarget.unanalyzed(1L),
                PropertyRefreshTarget.unanalyzed(3L));
    }

    @Test
    @DisplayName("페이지 크기보다 적게 오면 마지막 페이지로 보고 더 조회하지 않는다")
    void shortPageEndsReading() {
        page(null, 1L);

        List<PropertyRefreshTarget> targets = readAll(reader());

        assertThat(targets).containsExactly(PropertyRefreshTarget.unanalyzed(1L));
        verify(propertyMapper, times(1)).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("대상이 없으면 첫 읽기가 null 이다")
    void noTargets() {
        page(null);

        assertThat(reader().read()).isNull();
    }

    @Test
    @DisplayName("시세 변경 식별자는 만들 때가 아니라 첫 읽기 때 꺼낸다 — 읽기 단계가 적재 스텝보다 먼저 만들어진다")
    void priceChangedIdsAreReadLazily() {
        PropertyRefreshTargetReader reader = reader();
        report.recordLoad(new PropertyRefreshResult(List.of(), List.of(5L)));
        page(null);

        assertThat(reader.read()).isEqualTo(PropertyRefreshTarget.priceChanged(5L));
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("시세 변경 매물을 다 내주기 전에는 미판정 조회를 하지 않는다 — 앞에서 판정된 매물이 조회에서 빠지게")
    void unanalyzedQueryWaitsForPriceChanged() {
        report.recordLoad(new PropertyRefreshResult(List.of(), List.of(5L)));
        PropertyRefreshTargetReader reader = reader();

        reader.read();

        verify(propertyMapper, never()).selectUnanalyzedPropertyIds(any());
    }

    @Test
    @DisplayName("페이지 크기가 1 보다 작으면 만들 수 없다")
    void rejectsNonPositivePageSize() {
        assertThatThrownBy(() -> new PropertyRefreshTargetReader(propertyMapper, report, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PropertyRefreshTargetReader reader() {
        return new PropertyRefreshTargetReader(propertyMapper, report, PAGE_SIZE);
    }

    private void page(Long lastPropertyId, Long... propertyIds) {
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
