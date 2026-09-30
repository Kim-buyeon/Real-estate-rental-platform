package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.mapper.LedgerMapper;
import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link LedgerQueryService} — 매퍼 행을 응답으로 옮기는 것, 주거용 파생, 없는 매물 처리. */
class LedgerQueryServiceTest {

    private static final long PROPERTY_ID = 1024L;
    private static final OffsetDateTime UTC = OffsetDateTime.of(2026, 7, 27, 17, 10, 0, 0, ZoneOffset.UTC);

    private LedgerMapper ledgerMapper;
    private LedgerQueryService service;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(LedgerMapper.class);
        service = new LedgerQueryService(ledgerMapper);
    }

    @Test
    @DisplayName("매물 행이 없으면 PROPERTY_NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> service.getLedger(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    @Test
    @DisplayName("매물은 있는데 대장이 없으면 오류가 아니라 대장 항목이 전부 null(확인 불가)인 응답이다")
    void noLedgerYieldsNullFields() {
        when(ledgerMapper.selectLedger(PROPERTY_ID))
                .thenReturn(new LedgerRow(null, null, null, null, null, null, PROPERTY_ID, null));

        assertThat(service.getLedger(PROPERTY_ID)).isEqualTo(
                new LedgerResponse(PROPERTY_ID, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("위반건축물 확인 불가(null)는 null 그대로 낸다")
    void unverifiedViolationStaysNull() {
        when(ledgerMapper.selectLedger(PROPERTY_ID)).thenReturn(new LedgerRow(UTC, null, null,
                new BigDecimal("14544.66"), null, "공동주택", PROPERTY_ID, LedgerDataSource.BUILDING_HUB));

        LedgerResponse response = service.getLedger(PROPERTY_ID);

        assertThat(response.violationBuilding()).isNull();
        assertThat(response.isResidential()).isTrue();
        assertThat(response.dataSource()).isEqualTo(LedgerDataSource.BUILDING_HUB);
    }

    @Test
    @DisplayName("모든 필드를 옮기고 수집 시각을 서울 오프셋으로 맞춘다")
    void mapsAllFieldsInSeoulOffset() {
        when(ledgerMapper.selectLedger(PROPERTY_ID)).thenReturn(row("공동주택"));

        LedgerResponse response = service.getLedger(PROPERTY_ID);

        assertThat(response).isEqualTo(new LedgerResponse(PROPERTY_ID, "공동주택", true, true,
                new BigDecimal("480.20"), new BigDecimal("42.50"), LocalDate.of(2015, 4, 18),
                OffsetDateTime.of(2026, 7, 28, 2, 10, 0, 0, ZoneOffset.ofHours(9)), LedgerDataSource.MOCK));
    }

    @ParameterizedTest(name = "주용도 {0} → 주거용 {1}")
    @CsvSource({
        "단독주택, true",
        "공동주택, true",
        "업무시설, false",
        "제2종근린생활시설, false"
    })
    @DisplayName("주거용은 주용도가 단독주택 · 공동주택일 때만 참이다 — 건축법 시행령 별표 1 제1 · 2호")
    void residentialFollowsMainPurpose(String mainPurpose, boolean expected) {
        when(ledgerMapper.selectLedger(PROPERTY_ID)).thenReturn(row(mainPurpose));

        assertThat(service.getLedger(PROPERTY_ID).isResidential()).isEqualTo(expected);
    }

    private static LedgerRow row(String mainPurpose) {
        return new LedgerRow(UTC, LocalDate.of(2015, 4, 18), new BigDecimal("42.50"), new BigDecimal("480.20"),
                true, mainPurpose, PROPERTY_ID, LedgerDataSource.MOCK);
    }
}
