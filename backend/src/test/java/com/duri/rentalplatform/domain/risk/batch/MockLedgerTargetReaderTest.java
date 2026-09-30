package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.property.dto.condition.LedgerReplaceTargetCondition;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link MockLedgerTargetReader} — 관심 매물(Mock · 대장 없음) → Mock 전체 → 대장 없음 전체 순서, 식별자 커서, 관심 매물 단계
 * 식별자 건너뛰기, 상한에서 끝내기.
 */
class MockLedgerTargetReaderTest {

    private static final int PAGE_SIZE = 2;

    private LedgerMapper ledgerMapper;
    private BuildingLedgerDailyQuota dailyQuota;
    private MockLedgerTargetReader reader;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(LedgerMapper.class);
        dailyQuota = mock(BuildingLedgerDailyQuota.class);
        when(dailyQuota.remaining()).thenReturn(100L);
        reader = new MockLedgerTargetReader(ledgerMapper, dailyQuota, PAGE_SIZE);
    }

    @Test
    @DisplayName("관심 매물 → Mock 전체 → 대장 없음 전체 순서로 각각 커서로 다 읽고, 관심 매물 단계에서 내준 식별자는 건너뛴다")
    void wishlistedFirstThenAllSkippingSeen() {
        page(true, null, 3L, 7L);
        page(true, 7L, 9L);
        page(false, null, 1L, 3L);
        page(false, 3L, 5L, 7L);
        page(false, 7L, 8L, 9L);
        page(false, 9L);
        missingPage(null, 2L, 3L);
        missingPage(3L, 10L);

        // 대장 없음 단계에서도 관심 매물 단계에서 내준 3 은 건너뛴다.
        assertThat(readAll()).containsExactly(3L, 7L, 9L, 1L, 5L, 8L, 2L, 10L);

        InOrder order = inOrder(ledgerMapper);
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(true, null));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(true, 7L));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(false, null));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(false, 3L));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(false, 7L));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(condition(false, 9L));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(missing(null));
        order.verify(ledgerMapper).selectLedgerReplaceTargetIds(missing(3L));
    }

    @Test
    @DisplayName("관심 매물이 없으면 바로 전체 단계로 넘어간다")
    void noWishlistedGoesToAll() {
        page(true, null);
        page(false, null, 1L);

        assertThat(readAll()).containsExactly(1L);
    }

    @Test
    @DisplayName("대상이 없으면 첫 읽기가 null 이고, 끝난 뒤 다시 읽어도 조회하지 않는다")
    void noTargets() {
        page(true, null);
        page(false, null);

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        verify(ledgerMapper, times(3)).selectLedgerReplaceTargetIds(any());
    }

    @Test
    @DisplayName("오늘 상한을 다 썼으면 조회 없이 끝낸다")
    void quotaExhaustedBeforeStart() {
        when(dailyQuota.remaining()).thenReturn(0L);

        assertThat(reader.read()).isNull();
        verify(ledgerMapper, never()).selectLedgerReplaceTargetIds(any());
    }

    @Test
    @DisplayName("도중에 상한이 0 이 되면 남은 대상이 있어도 그다음 읽기에서 끝낸다 — 다시 읽어도 null")
    void quotaExhaustedMidway() {
        page(true, null, 3L, 7L);
        when(dailyQuota.remaining()).thenReturn(5L, 0L, 5L);

        assertThat(reader.read()).isEqualTo(3L);
        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("페이지 크기가 1 미만이면 만들지 않는다")
    void rejectsNonPositivePageSize() {
        assertThatThrownBy(() -> new MockLedgerTargetReader(ledgerMapper, dailyQuota, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<Long> readAll() {
        List<Long> read = new ArrayList<>();
        for (Long id = reader.read(); id != null; id = reader.read()) {
            read.add(id);
        }
        return read;
    }

    private void page(boolean wishlistedOnly, Long lastPropertyId, Long... propertyIds) {
        when(ledgerMapper.selectLedgerReplaceTargetIds(condition(wishlistedOnly, lastPropertyId)))
                .thenReturn(List.of(propertyIds));
    }

    private void missingPage(Long lastPropertyId, Long... propertyIds) {
        when(ledgerMapper.selectLedgerReplaceTargetIds(missing(lastPropertyId))).thenReturn(List.of(propertyIds));
    }

    /** 관심 매물 단계는 Mock · 대장 없음 둘 다, 전체 단계는 Mock 만. */
    private static LedgerReplaceTargetCondition condition(boolean wishlistedOnly, Long lastPropertyId) {
        return new LedgerReplaceTargetCondition(LedgerDataSource.MOCK, wishlistedOnly, wishlistedOnly, lastPropertyId,
                PAGE_SIZE);
    }

    private static LedgerReplaceTargetCondition missing(Long lastPropertyId) {
        return new LedgerReplaceTargetCondition(null, true, false, lastPropertyId, PAGE_SIZE);
    }
}
