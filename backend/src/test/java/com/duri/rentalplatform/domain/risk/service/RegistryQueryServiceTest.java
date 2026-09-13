package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.dto.response.RegistryResponse;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.domain.risk.mapper.RegistryMapper;
import com.duri.rentalplatform.domain.risk.vo.RegistryHeaderRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RegistryQueryService} — 매퍼 결과를 응답으로 묶는 것과 없는 매물 처리. */
class RegistryQueryServiceTest {

    private static final long PROPERTY_ID = 1024L;

    private RegistryMapper registryMapper;
    private RegistryQueryService service;

    @BeforeEach
    void setUp() {
        registryMapper = mock(RegistryMapper.class);
        service = new RegistryQueryService(registryMapper);
    }

    @Test
    @DisplayName("표제부가 없으면 PROPERTY_NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> service.getRegistry(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    @Test
    @DisplayName("갑구 · 을구를 묶고 수집 시각을 서울 오프셋으로 맞춘다")
    void assemblesResponseInSeoulOffset() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 7, 28, 18, 0, 0, 0, ZoneOffset.UTC);
        List<RegistryResponse.Ownership> ownerships = List.of(new RegistryResponse.Ownership(
                2, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대", LocalDate.of(2019, 3, 11), "매매", true));
        List<RegistryResponse.Mortgage> mortgages = List.of(new RegistryResponse.Mortgage(
                1, "○○은행", 250_000_000L, LocalDate.of(2019, 3, 11), true));
        when(registryMapper.selectHeader(PROPERTY_ID))
                .thenReturn(new RegistryHeaderRow(RegistryDataSource.MOCK, utc, PROPERTY_ID));
        when(registryMapper.selectOwnerships(PROPERTY_ID)).thenReturn(ownerships);
        when(registryMapper.selectMortgages(PROPERTY_ID)).thenReturn(mortgages);

        RegistryResponse response = service.getRegistry(PROPERTY_ID);

        assertThat(response.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(response.ownerships()).isEqualTo(ownerships);
        assertThat(response.mortgages()).isEqualTo(mortgages);
        assertThat(response.collectedAt()).isEqualTo(OffsetDateTime.of(2026, 7, 29, 3, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(response.dataSource()).isEqualTo(RegistryDataSource.MOCK);
    }
}
