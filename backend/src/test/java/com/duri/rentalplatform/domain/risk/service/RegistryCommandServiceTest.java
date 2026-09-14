package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.MortgageHistory;
import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import com.duri.rentalplatform.domain.risk.enums.MortgageRightType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;
import com.duri.rentalplatform.domain.risk.event.RegistryChangedEvent;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.MortgageHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.OwnershipHistoryRepository;
import com.duri.rentalplatform.external.registry.RegistryClient;
import com.duri.rentalplatform.external.registry.RegistryDocument;
import com.duri.rentalplatform.external.registry.RegistryDocument.MortgageEntry;
import com.duri.rentalplatform.external.registry.RegistryDocument.OwnershipEntry;
import com.duri.rentalplatform.external.registry.RegistryLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link RegistryCommandService} 의 수집 분기. 저장소 · 클라이언트 · 트랜잭션 관리자를 모두 목으로 둔다.
 *
 * <p>트랜잭션 관리자를 목으로 두는 이유 — 외부 호출이 트랜잭션 <b>밖</b>에서 일어나는지를 호출 순서로 확인할 수
 * 있다. 읽기 트랜잭션 커밋 → 클라이언트 호출 → 쓰기 트랜잭션 시작 순이어야 한다.
 */
class RegistryCommandServiceTest {

    private static final long PROPERTY_ID = 1024L;

    private RegistryClient registryClient;
    private PropertyRepository propertyRepository;
    private BuildingRegistryRepository buildingRegistryRepository;
    private OwnershipHistoryRepository ownershipHistoryRepository;
    private MortgageHistoryRepository mortgageHistoryRepository;
    private PlatformTransactionManager transactionManager;
    private AuditingHandler auditingHandler;
    private ApplicationEventPublisher eventPublisher;
    private RegistryCommandService service;

    @BeforeEach
    void setUp() {
        registryClient = mock(RegistryClient.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingRegistryRepository = mock(BuildingRegistryRepository.class);
        ownershipHistoryRepository = mock(OwnershipHistoryRepository.class);
        mortgageHistoryRepository = mock(MortgageHistoryRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        auditingHandler = mock(AuditingHandler.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new RegistryCommandService(registryClient, propertyRepository, buildingRegistryRepository,
                ownershipHistoryRepository, mortgageHistoryRepository, auditingHandler, eventPublisher,
                transactionManager);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND 이고 외부를 부르지 않는다")
    void propertyNotFound() {
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
        verify(registryClient, never()).fetch(any());
    }

    @Test
    @DisplayName("이미 수집한 매물은 외부를 부르지도 저장하지도 않는다")
    void alreadyCollected() {
        givenProperty();
        when(buildingRegistryRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(true);

        service.collectIfAbsent(PROPERTY_ID);

        verify(registryClient, never()).fetch(any());
        verify(buildingRegistryRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("처음 조회면 매물 정보로 등기를 떼어 표제부 · 갑구 · 을구를 옮겨 저장한다")
    void collectsAndSaves() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        when(buildingRegistryRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.collectIfAbsent(PROPERTY_ID);

        ArgumentCaptor<RegistryLookup> lookup = ArgumentCaptor.forClass(RegistryLookup.class);
        verify(registryClient).fetch(lookup.capture());
        assertThat(lookup.getValue()).isEqualTo(new RegistryLookup(PROPERTY_ID, naturalKey(), "김임대",
                PropertyType.OFFICETEL, 340_000_000L));

        ArgumentCaptor<BuildingRegistry> registry = ArgumentCaptor.forClass(BuildingRegistry.class);
        verify(buildingRegistryRepository).saveAndFlush(registry.capture());
        assertThat(registry.getValue().getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(registry.getValue().getBuildingPurpose()).isEqualTo("업무시설");
        assertThat(registry.getValue().getBuildingStructure()).isEqualTo("철근콘크리트구조");
        assertThat(registry.getValue().getRegistryAddress()).isEqualTo("서울특별시 시험구 시험로 1");
        assertThat(registry.getValue().getExclusiveArea()).isEqualByComparingTo("42.50");
        assertThat(registry.getValue().getDataSource()).isEqualTo(RegistryDataSource.MOCK);

        List<OwnershipHistory> ownerships = captureList(ownershipHistoryRepository);
        assertThat(ownerships).hasSize(2);
        OwnershipHistory seizure = ownerships.get(1);
        assertThat(seizure.getRegistry()).isSameAs(registry.getValue());
        assertThat(seizure.getRankNo()).isEqualTo(2);
        assertThat(seizure.getRightType()).isEqualTo(OwnershipRightType.SEIZURE);
        assertThat(seizure.getOwnerName()).isEqualTo("○○세무서");
        assertThat(seizure.getOwnershipDate()).isEqualTo(LocalDate.of(2021, 5, 3));
        assertThat(seizure.getRegistrationCause()).isEqualTo("압류");
        assertThat(seizure.isSeizure()).isTrue();
        assertThat(seizure.isProvisionalSeizure()).isFalse();
        assertThat(seizure.isCurrent()).isTrue();
        assertThat(ownerships.get(0).isSeizure()).isFalse();

        List<MortgageHistory> mortgages = captureList(mortgageHistoryRepository);
        assertThat(mortgages).hasSize(1);
        MortgageHistory mortgage = mortgages.get(0);
        assertThat(mortgage.getPriorityNo()).isEqualTo(1);
        assertThat(mortgage.getRightType()).isEqualTo(MortgageRightType.MORTGAGE);
        assertThat(mortgage.getMortgageCreditor()).isEqualTo("○○은행");
        assertThat(mortgage.getDebtorName()).isEqualTo("김임대");
        assertThat(mortgage.getReceiptDate()).isEqualTo(LocalDate.of(2019, 3, 11));
        assertThat(mortgage.getRegistrationCause()).isEqualTo("설정계약");
        assertThat(mortgage.getMortgageAmount()).isEqualTo(100_000_000L);
        assertThat(mortgage.getMaxBondAmount()).isEqualTo(120_000_000L);
        assertThat(mortgage.getPriorTenantDeposit()).isZero();
        assertThat(mortgage.isSeniorDebt()).isTrue();
        assertThat(mortgage.isTenancyRight()).isFalse();
        assertThat(mortgage.isActive()).isFalse();
    }

    @Test
    @DisplayName("외부 호출은 읽기 트랜잭션이 끝난 뒤, 쓰기 트랜잭션이 시작되기 전에 일어난다")
    void fetchHappensOutsideTransactions() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        when(buildingRegistryRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.collectIfAbsent(PROPERTY_ID);

        InOrder order = inOrder(transactionManager, registryClient, buildingRegistryRepository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
        order.verify(registryClient).fetch(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(buildingRegistryRepository).saveAndFlush(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("동시 첫 조회로 UNIQUE 에 걸려도 이미 저장된 등기가 있으면 예외 없이 끝난다")
    void concurrentFirstCollectionIsAbsorbed() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        when(buildingRegistryRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false, true);
        when(buildingRegistryRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_building_registry_property_id"));

        service.collectIfAbsent(PROPERTY_ID);

        verify(ownershipHistoryRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("무결성 위반인데 저장된 등기가 없으면 삼키지 않는다")
    void otherIntegrityViolationIsRethrown() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        when(buildingRegistryRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false);
        when(buildingRegistryRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- 다시 떼기 ----------

    @Test
    @DisplayName("다시 뗀 갑구 · 을구가 저장된 것과 같으면(순서만 달라도) 아무것도 쓰지 않고 이벤트도 없다")
    void refreshUnchanged() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        BuildingRegistry registry = storedRegistry();
        List<OwnershipHistory> ownerships = List.of(storedOwnerships(registry).get(1), storedOwnerships(registry).get(0));
        List<MortgageHistory> mortgages = storedMortgages(registry);
        when(ownershipHistoryRepository.findByRegistry(registry)).thenReturn(ownerships);
        when(mortgageHistoryRepository.findByRegistry(registry)).thenReturn(mortgages);

        RegistryRefreshOutcome outcome = service.refresh(PROPERTY_ID);

        assertThat(outcome).isEqualTo(RegistryRefreshOutcome.UNCHANGED);
        assertThat(outcome.isModified()).isFalse();
        verify(ownershipHistoryRepository, never()).deleteAllInBatch(anyList());
        verify(mortgageHistoryRepository, never()).deleteAllInBatch(anyList());
        verify(ownershipHistoryRepository, never()).saveAll(anyList());
        verifyNoInteractions(auditingHandler, eventPublisher);
    }

    @Test
    @DisplayName("을구 한 건의 말소 여부만 달라도 이력을 교체하고 수집 시각을 갱신한 뒤 변동 전 · 후 요약을 담은 이벤트를 발행한다")
    void refreshChangedReplacesHistories() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        BuildingRegistry registry = storedRegistry();
        List<OwnershipHistory> ownerships = storedOwnerships(registry);
        List<MortgageHistory> mortgages = List.of(MortgageHistory.record(registry, 1, MortgageRightType.MORTGAGE,
                "○○은행", "김임대", LocalDate.of(2019, 3, 11), "설정계약", 100_000_000L, 120_000_000L, 0L, true));
        when(ownershipHistoryRepository.findByRegistry(registry)).thenReturn(ownerships);
        when(mortgageHistoryRepository.findByRegistry(registry)).thenReturn(mortgages);
        LocalDateTime refreshedAt = LocalDateTime.of(2026, 9, 14, 3, 0);
        when(auditingHandler.markModified(registry)).thenAnswer(inv -> {
            ReflectionTestUtils.setField(registry, "updatedAt", refreshedAt);
            return registry;
        });

        RegistryRefreshOutcome outcome = service.refresh(PROPERTY_ID);

        assertThat(outcome).isEqualTo(RegistryRefreshOutcome.CHANGED);
        InOrder order = inOrder(transactionManager, registryClient, ownershipHistoryRepository,
                mortgageHistoryRepository, auditingHandler, buildingRegistryRepository, eventPublisher);
        order.verify(transactionManager).commit(any());
        order.verify(registryClient).fetch(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(ownershipHistoryRepository).deleteAllInBatch(ownerships);
        order.verify(mortgageHistoryRepository).deleteAllInBatch(mortgages);
        order.verify(ownershipHistoryRepository).saveAll(anyList());
        order.verify(mortgageHistoryRepository).saveAll(anyList());
        order.verify(auditingHandler).markModified(registry);
        order.verify(buildingRegistryRepository).flush();
        ArgumentCaptor<RegistryChangedEvent> event = ArgumentCaptor.forClass(RegistryChangedEvent.class);
        order.verify(eventPublisher).publishEvent(event.capture());
        order.verify(transactionManager).commit(any());
        // 저장돼 있던 을구 한 건은 유효, 새로 뗀 것은 말소 — 갑구 유효 2건은 그대로다.
        assertThat(event.getValue().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(event.getValue().beforeSummary()).matches("갑구 2 · 을구 1 · [0-9a-f]{8}");
        assertThat(event.getValue().afterSummary()).matches("갑구 2 · 을구 0 · [0-9a-f]{8}");
        assertThat(event.getValue().detectedAt()).isEqualTo(refreshedAt);

        List<MortgageHistory> saved = captureList(mortgageHistoryRepository);
        assertThat(saved).singleElement().satisfies(mortgage -> {
            assertThat(mortgage.getRegistry()).isSameAs(registry);
            assertThat(mortgage.isActive()).isFalse();
        });
        assertThat(captureList(ownershipHistoryRepository)).hasSize(2);
        verify(buildingRegistryRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("소유권 이전으로 유효 건수가 같아도 변동 전 · 후 요약이 다르다")
    void refreshOwnershipTransferSummariesDiffer() {
        givenProperty();
        BuildingRegistry registry = storedRegistry();
        when(ownershipHistoryRepository.findByRegistry(registry)).thenReturn(storedOwnerships(registry));
        when(mortgageHistoryRepository.findByRegistry(registry)).thenReturn(storedMortgages(registry));
        // 김임대 → 박새주인 이전. 이전 소유 등기는 현재 소유자가 아니게 되고, 갑구 유효 건수는 2건 그대로다.
        RegistryDocument transferred = new RegistryDocument("업무시설", "철근콘크리트구조", "서울특별시 시험구 시험로 1",
                new BigDecimal("42.50"), RegistryDataSource.MOCK,
                List.of(
                        new OwnershipEntry(1, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대",
                                LocalDate.of(2019, 3, 11), "매매", false),
                        new OwnershipEntry(2, OwnershipRightType.SEIZURE, "○○세무서",
                                LocalDate.of(2021, 5, 3), "압류", true),
                        new OwnershipEntry(3, OwnershipRightType.OWNERSHIP_TRANSFER, "박새주인",
                                LocalDate.of(2026, 9, 1), "매매", true)),
                document().mortgages());
        when(registryClient.fetch(any())).thenReturn(transferred);

        service.refresh(PROPERTY_ID);

        ArgumentCaptor<RegistryChangedEvent> event = ArgumentCaptor.forClass(RegistryChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().beforeSummary()).startsWith("갑구 2 · 을구 0 · ");
        assertThat(event.getValue().afterSummary()).startsWith("갑구 2 · 을구 0 · ");
        assertThat(event.getValue().afterSummary()).isNotEqualTo(event.getValue().beforeSummary());
        assertThat(event.getValue().afterSummary()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("저장된 등기가 없으면 새로 저장하고 COLLECTED 이며 변동 이벤트는 없다")
    void refreshCollectsWhenAbsent() {
        givenProperty();
        when(registryClient.fetch(any())).thenReturn(document());
        when(buildingRegistryRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());
        when(buildingRegistryRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RegistryRefreshOutcome outcome = service.refresh(PROPERTY_ID);

        assertThat(outcome).isEqualTo(RegistryRefreshOutcome.COLLECTED);
        assertThat(outcome.isModified()).isTrue();
        verify(buildingRegistryRepository).saveAndFlush(any());
        assertThat(captureList(ownershipHistoryRepository)).hasSize(2);
        assertThat(captureList(mortgageHistoryRepository)).hasSize(1);
        verifyNoInteractions(auditingHandler, eventPublisher);
    }

    @Test
    @DisplayName("외부 조회가 실패하면 그대로 올리고 쓰기 트랜잭션을 열지 않는다")
    void refreshPropagatesExternalFailure() {
        givenProperty();
        when(registryClient.fetch(any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> service.refresh(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        verify(transactionManager).getTransaction(any());
        verify(buildingRegistryRepository, never()).findByPropertyId(any());
        verify(ownershipHistoryRepository, never()).deleteAllInBatch(anyList());
        verifyNoInteractions(auditingHandler, eventPublisher);
    }

    // ---------- 픽스처 ----------

    private BuildingRegistry storedRegistry() {
        BuildingRegistry registry = BuildingRegistry.collect(PROPERTY_ID, "업무시설", "철근콘크리트구조",
                "서울특별시 시험구 시험로 1", new BigDecimal("42.50"), RegistryDataSource.MOCK);
        when(buildingRegistryRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(registry));
        return registry;
    }

    /** {@link #document()} 의 갑구와 같은 내용의 저장 행. */
    private static List<OwnershipHistory> storedOwnerships(BuildingRegistry registry) {
        return List.of(
                OwnershipHistory.record(registry, 1, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대",
                        LocalDate.of(2019, 3, 11), "매매", true),
                OwnershipHistory.record(registry, 2, OwnershipRightType.SEIZURE, "○○세무서",
                        LocalDate.of(2021, 5, 3), "압류", true));
    }

    /** {@link #document()} 의 을구와 같은 내용의 저장 행. */
    private static List<MortgageHistory> storedMortgages(BuildingRegistry registry) {
        return List.of(MortgageHistory.record(registry, 1, MortgageRightType.MORTGAGE, "○○은행", "김임대",
                LocalDate.of(2019, 3, 11), "설정계약", 100_000_000L, 120_000_000L, 0L, false));
    }

    private void givenProperty() {
        Property property = mock(Property.class);
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn("OFFICETEL");
        when(property.getPropertyId()).thenReturn(PROPERTY_ID);
        when(property.naturalKey()).thenReturn(naturalKey());
        when(property.getLandlordName()).thenReturn("김임대");
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(property.getMarketPrice()).thenReturn(340_000_000L);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
    }

    private static PropertyNaturalKey naturalKey() {
        return new PropertyNaturalKey("서울특별시 시험구 시험로 1", new BigDecimal("42.50"), 3, 230_000_000L, 0L);
    }

    private static RegistryDocument document() {
        return new RegistryDocument("업무시설", "철근콘크리트구조", "서울특별시 시험구 시험로 1",
                new BigDecimal("42.50"), RegistryDataSource.MOCK,
                List.of(
                        new OwnershipEntry(1, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대",
                                LocalDate.of(2019, 3, 11), "매매", true),
                        new OwnershipEntry(2, OwnershipRightType.SEIZURE, "○○세무서",
                                LocalDate.of(2021, 5, 3), "압류", true)),
                List.of(new MortgageEntry(1, MortgageRightType.MORTGAGE, "○○은행", "김임대",
                        LocalDate.of(2019, 3, 11), "설정계약", 100_000_000L, 120_000_000L, 0L, false)));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> captureList(org.springframework.data.jpa.repository.JpaRepository<T, Long> repository) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }
}
