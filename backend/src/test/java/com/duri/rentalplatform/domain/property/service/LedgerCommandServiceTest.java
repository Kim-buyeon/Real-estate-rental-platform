package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerClient;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link LedgerCommandService} 의 수집 분기. 저장소 · 클라이언트 · 트랜잭션 관리자를 모두 목으로 둔다.
 *
 * <p>트랜잭션 관리자를 목으로 두어 외부 호출이 트랜잭션 <b>밖</b>에서 일어나는지를 호출 순서로 확인한다.
 */
class LedgerCommandServiceTest {

    private static final long PROPERTY_ID = 1024L;

    private BuildingLedgerClient client;
    private PropertyRepository propertyRepository;
    private BuildingLedgerRepository buildingLedgerRepository;
    private PlatformTransactionManager transactionManager;
    private LedgerCommandService service;

    @BeforeEach
    void setUp() {
        client = mock(BuildingLedgerClient.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingLedgerRepository = mock(BuildingLedgerRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new LedgerCommandService(client, propertyRepository, buildingLedgerRepository, transactionManager);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND 이고 외부를 부르지 않는다")
    void propertyNotFound() {
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
        verify(client, never()).fetch(any());
    }

    @Test
    @DisplayName("이미 수집한 매물은 외부를 부르지도 저장하지도 않는다")
    void alreadyCollected() {
        givenProperty();
        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(true);

        service.collectIfAbsent(PROPERTY_ID);

        verify(client, never()).fetch(any());
        verify(buildingLedgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("처음 조회면 매물 정보로 대장을 떼어 모든 항목을 옮겨 저장한다")
    void collectsAndSaves() {
        givenProperty();
        when(client.fetch(any())).thenReturn(document());

        service.collectIfAbsent(PROPERTY_ID);

        ArgumentCaptor<BuildingLedgerLookup> lookup = ArgumentCaptor.forClass(BuildingLedgerLookup.class);
        verify(client).fetch(lookup.capture());
        assertThat(lookup.getValue())
                .isEqualTo(new BuildingLedgerLookup(PROPERTY_ID, naturalKey(), "김임대", PropertyType.APARTMENT));

        ArgumentCaptor<BuildingLedger> saved = ArgumentCaptor.forClass(BuildingLedger.class);
        verify(buildingLedgerRepository).saveAndFlush(saved.capture());
        BuildingLedger ledger = saved.getValue();
        assertThat(ledger.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(ledger.getLedgerAddress()).isEqualTo("서울특별시 시험구 시험로 1");
        assertThat(ledger.getOwnerName()).isEqualTo("김임대");
        assertThat(ledger.getBuildingPurpose()).isEqualTo("공동주택");
        assertThat(ledger.getBuildingStructure()).isEqualTo("철근콘크리트구조");
        assertThat(ledger.getBuildingArea()).isEqualByComparingTo("600.30");
        assertThat(ledger.getTotalFloorArea()).isEqualByComparingTo("2550.00");
        assertThat(ledger.getExclusiveArea()).isEqualByComparingTo("42.50");
        assertThat(ledger.getApprovalDate()).isEqualTo(LocalDate.of(1998, 4, 18));
        assertThat(ledger.isViolation()).isTrue();
        assertThat(ledger.getDataSource()).isEqualTo(LedgerDataSource.MOCK);
    }

    @Test
    @DisplayName("외부 호출은 읽기 트랜잭션이 끝난 뒤, 쓰기 트랜잭션이 시작되기 전에 일어난다")
    void fetchHappensOutsideTransactions() {
        givenProperty();
        when(client.fetch(any())).thenReturn(document());

        service.collectIfAbsent(PROPERTY_ID);

        InOrder order = inOrder(transactionManager, client, buildingLedgerRepository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
        order.verify(client).fetch(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(buildingLedgerRepository).saveAndFlush(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("연동이 실패하면 EXTERNAL_API_UNAVAILABLE 을 그대로 올리고 아무것도 저장하지 않는다")
    void externalFailureSavesNothing() {
        givenProperty();
        when(client.fetch(any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        verify(buildingLedgerRepository, never()).saveAndFlush(any());
        verify(buildingLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("동시 첫 조회로 UNIQUE 에 걸려도 이미 저장된 대장이 있으면 예외 없이 끝난다")
    void concurrentFirstCollectionIsAbsorbed() {
        givenProperty();
        when(client.fetch(any())).thenReturn(document());
        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false, true);
        when(buildingLedgerRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_building_ledger_property_id"));

        service.collectIfAbsent(PROPERTY_ID);

        verify(buildingLedgerRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("무결성 위반인데 저장된 대장이 없으면 삼키지 않는다")
    void otherIntegrityViolationIsRethrown() {
        givenProperty();
        when(client.fetch(any())).thenReturn(document());
        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false);
        when(buildingLedgerRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- 픽스처 ----------

    private void givenProperty() {
        Property property = mock(Property.class);
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn("APARTMENT");
        when(property.getPropertyId()).thenReturn(PROPERTY_ID);
        when(property.naturalKey()).thenReturn(naturalKey());
        when(property.getLandlordName()).thenReturn("김임대");
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
    }

    private static PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey("서울특별시 시험구 시험로 1", new BigDecimal("42.50"), 3, 230_000_000L, 0L);
    }

    private static BuildingLedgerDocument document() {
        return new BuildingLedgerDocument("서울특별시 시험구 시험로 1", "김임대", "공동주택", "철근콘크리트구조",
                new BigDecimal("600.30"), new BigDecimal("2550.00"), new BigDecimal("42.50"),
                LocalDate.of(1998, 4, 18), true, LedgerDataSource.MOCK);
    }
}
