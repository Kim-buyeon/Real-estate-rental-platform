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
import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.LedgerReplacement;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerClient;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDailyQuota;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerLookup;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerRateLimitedException;
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
    private AddressNormalizeClient addressClient;
    private PropertyRepository propertyRepository;
    private BuildingLedgerRepository buildingLedgerRepository;
    private PlatformTransactionManager transactionManager;
    private BuildingLedgerDailyQuota dailyQuota;
    private LedgerCommandService service;

    @BeforeEach
    void setUp() {
        client = mock(BuildingLedgerClient.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingLedgerRepository = mock(BuildingLedgerRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        addressClient = mock(AddressNormalizeClient.class);
        dailyQuota = mock(BuildingLedgerDailyQuota.class);
        when(dailyQuota.remaining()).thenReturn(100L);
        service = new LedgerCommandService(client, addressClient, propertyRepository, buildingLedgerRepository,
                dailyQuota, transactionManager);
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

        assertThat(service.collectIfAbsent(PROPERTY_ID)).isFalse();

        verify(client, never()).fetch(any());
        verify(buildingLedgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("처음 조회면 매물 정보로 대장을 떼어 모든 항목을 옮겨 저장한다")
    void collectsAndSaves() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(document()));

        assertThat(service.collectIfAbsent(PROPERTY_ID)).isTrue();

        ArgumentCaptor<BuildingLedgerLookup> lookup = ArgumentCaptor.forClass(BuildingLedgerLookup.class);
        verify(client).fetch(lookup.capture());
        assertThat(lookup.getValue())
                .isEqualTo(new BuildingLedgerLookup(PROPERTY_ID, naturalKey(), "김임대", PropertyType.APARTMENT, null));

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
        assertThat(ledger.getViolation()).isTrue();
        assertThat(ledger.getDataSource()).isEqualTo(LedgerDataSource.MOCK);
    }

    @Test
    @DisplayName("초당 한도 예외면 저장 없이 끝낸다 — 503 이 아니라 대장 없이 판정한다(PROP-04)")
    void rateLimitedCollectsNothing() {
        givenProperty();
        when(client.fetch(any())).thenThrow(new BuildingLedgerRateLimitedException("시험"));

        assertThat(service.collectIfAbsent(PROPERTY_ID)).isFalse();

        verify(buildingLedgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("외부 호출은 읽기 트랜잭션이 끝난 뒤, 쓰기 트랜잭션이 시작되기 전에 일어난다")
    void fetchHappensOutsideTransactions() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(document()));

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
        when(client.fetch(any())).thenReturn(Optional.of(document()));
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
        when(client.fetch(any())).thenReturn(Optional.of(document()));
        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false);
        when(buildingLedgerRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- 조회 키 · 뗄 대장이 없을 때 · 대장 주소 정규화(PROP-04) ----------

    @Test
    @DisplayName("매물의 대장 조회 키를 조회 값에 옮겨 넘긴다")
    void passesLedgerKey() {
        LedgerLookupKey key = new LedgerLookupKey("11110", "17400", "0702", "0000");
        givenProperty().ledgerKey(key);
        when(client.fetch(any())).thenReturn(Optional.of(document()));

        service.collectIfAbsent(PROPERTY_ID);

        ArgumentCaptor<BuildingLedgerLookup> lookup = ArgumentCaptor.forClass(BuildingLedgerLookup.class);
        verify(client).fetch(lookup.capture());
        assertThat(lookup.getValue().ledgerKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("뗄 대장이 없으면(빈 값) 오류 없이 끝나고 아무것도 저장하지 않는다 — 대장 행을 지어내지 않는다")
    void noDocumentSavesNothing() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.empty());

        service.collectIfAbsent(PROPERTY_ID);

        verify(buildingLedgerRepository, never()).saveAndFlush(any());
        verify(addressClient, never()).normalize(any());
    }

    @Test
    @DisplayName("건축HUB 대장은 지번 주소를 주소 정규화에 태워 도로명 주소로 저장하고, 위반건축물 null 은 null 로 저장한다")
    void normalizesBuildingHubAddress() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(buildingHubDocument()));
        when(addressClient.normalize("서울특별시 종로구 창신동 702")).thenReturn(Optional.of(new NormalizedAddress(
                "서울특별시 종로구 동망산길 19 (창신동)", "서울특별시 종로구 창신동 702", "종로구", "창신동", null,
                "1111017400", "TEST_JUSO")));

        service.collectIfAbsent(PROPERTY_ID);

        ArgumentCaptor<BuildingLedger> saved = ArgumentCaptor.forClass(BuildingLedger.class);
        verify(buildingLedgerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLedgerAddress()).isEqualTo("서울특별시 종로구 동망산길 19 (창신동)");
        assertThat(saved.getValue().getViolation()).isNull();
        assertThat(saved.getValue().getOwnerName()).isNull();
        assertThat(saved.getValue().getDataSource()).isEqualTo(LedgerDataSource.BUILDING_HUB);
    }

    @Test
    @DisplayName("정규화 결과가 없으면 대장 원문 주소를 그대로 저장한다")
    void keepsRawAddressWhenNormalizationFindsNothing() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(buildingHubDocument()));
        when(addressClient.normalize(any())).thenReturn(Optional.empty());

        service.collectIfAbsent(PROPERTY_ID);

        ArgumentCaptor<BuildingLedger> saved = ArgumentCaptor.forClass(BuildingLedger.class);
        verify(buildingLedgerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLedgerAddress()).isEqualTo("서울특별시 종로구 창신동 702");
    }

    @Test
    @DisplayName("정규화 연동이 실패하면 그대로 올리고 아무것도 저장하지 않는다")
    void normalizationFailureSavesNothing() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(buildingHubDocument()));
        when(addressClient.normalize(any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatThrownBy(() -> service.collectIfAbsent(PROPERTY_ID)).isInstanceOf(BusinessException.class);
        verify(buildingLedgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Mock 대장은 이미 매물 주소라 주소 정규화를 부르지 않는다")
    void doesNotNormalizeMockAddress() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(document()));

        service.collectIfAbsent(PROPERTY_ID);

        verify(addressClient, never()).normalize(any());
    }

    // ---------- Mock 대장 교체(PROP-04) ----------

    private static final LedgerLookupKey KEY = new LedgerLookupKey("11110", "17400", "0702", "0000");

    @Test
    @DisplayName("교체용 떼기: 대장이 Mock 이 아니면 NOT_MOCK, 대장 행이 없으면 NO_LEDGER — 둘 다 외부를 부르지 않는다")
    void fetchReplacementSkipsNonMock() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.BUILDING_HUB);

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome()).isEqualTo(LedgerReplacementOutcome.NOT_MOCK);

        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());
        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome()).isEqualTo(LedgerReplacementOutcome.NO_LEDGER);
        verify(client, never()).fetch(any());
    }

    @Test
    @DisplayName("교체용 떼기: 초당 한도 예외면 RATE_LIMITED — 없음(NOT_FOUND)으로 Mock 행을 지우지 않는다")
    void rateLimitedReplacementKeepsMock() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.MOCK);
        when(client.fetch(any())).thenThrow(new BuildingLedgerRateLimitedException("시험"));

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome())
                .isEqualTo(LedgerReplacementOutcome.RATE_LIMITED);
    }

    @Test
    @DisplayName("교체용 떼기: 건축HUB 대장을 떼면 주소를 정규화해 FETCHED 로 돌려주고 저장하지 않는다")
    void fetchReplacementReturnsNormalizedDocument() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.MOCK);
        when(client.fetch(any())).thenReturn(Optional.of(buildingHubDocument()));
        when(addressClient.normalize("서울특별시 종로구 창신동 702")).thenReturn(Optional.of(new NormalizedAddress(
                "서울특별시 종로구 동망산길 19 (창신동)", "서울특별시 종로구 창신동 702", "종로구", "창신동", null,
                "1111017400", "TEST_JUSO")));

        LedgerReplacement replacement = service.fetchMockReplacement(PROPERTY_ID);

        assertThat(replacement.outcome()).isEqualTo(LedgerReplacementOutcome.FETCHED);
        assertThat(replacement.document().ledgerAddress()).isEqualTo("서울특별시 종로구 동망산길 19 (창신동)");
        assertThat(replacement.document().dataSource()).isEqualTo(LedgerDataSource.BUILDING_HUB);
        verify(buildingLedgerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("교체용 떼기: 빈 값이고 상한이 남아 있으면 NOT_FOUND — 뗄 대장이 없다")
    void fetchReplacementNotFound() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.MOCK);
        when(client.fetch(any())).thenReturn(Optional.empty());

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome()).isEqualTo(LedgerReplacementOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("교체용 떼기: 상한이 이미 0 이면 부르지 않고 QUOTA_EXHAUSTED")
    void fetchReplacementStopsWhenQuotaAlreadyExhausted() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.MOCK);
        when(dailyQuota.remaining()).thenReturn(0L);

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome())
                .isEqualTo(LedgerReplacementOutcome.QUOTA_EXHAUSTED);
        verify(client, never()).fetch(any());
    }

    @Test
    @DisplayName("교체용 떼기: 빈 값 뒤 상한이 0 이면 상한에 걸린 빈 값으로 보고 QUOTA_EXHAUSTED — 없음으로 지우지 않는다")
    void emptyAfterQuotaExhaustedIsQuota() {
        givenProperty().ledgerKey(KEY);
        givenStoredLedger(LedgerDataSource.MOCK);
        when(dailyQuota.remaining()).thenReturn(1L, 0L);
        when(client.fetch(any())).thenReturn(Optional.empty());

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome())
                .isEqualTo(LedgerReplacementOutcome.QUOTA_EXHAUSTED);
    }

    @Test
    @DisplayName("교체용 떼기: 조회 키가 없으면 상한과 무관하게 NOT_FOUND — 클라이언트가 부르지 않는다")
    void noKeyIsNotFoundRegardlessOfQuota() {
        givenProperty();
        givenStoredLedger(LedgerDataSource.MOCK);
        when(dailyQuota.remaining()).thenReturn(0L);
        when(client.fetch(any())).thenReturn(Optional.empty());

        assertThat(service.fetchMockReplacement(PROPERTY_ID).outcome()).isEqualTo(LedgerReplacementOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("교체용 떼기: 매물이 없으면 PROPERTY_NOT_FOUND")
    void fetchReplacementPropertyNotFound() {
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetchMockReplacement(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    @Test
    @DisplayName("교체 저장: Mock 행의 모든 항목을 뗀 대장으로 바꾼다 — null 은 null 로 덮는다")
    void replaceMockOverwritesAllFields() {
        BuildingLedger ledger = BuildingLedger.collect(PROPERTY_ID, "서울특별시 시험구 시험로 1", "김임대", "공동주택",
                "철근콘크리트구조", new BigDecimal("600.30"), new BigDecimal("2550.00"), new BigDecimal("42.50"),
                LocalDate.of(1998, 4, 18), true, LedgerDataSource.MOCK);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));

        boolean replaced = service.replaceMock(PROPERTY_ID, buildingHubDocument());

        assertThat(replaced).isTrue();
        assertThat(ledger.getLedgerAddress()).isEqualTo("서울특별시 종로구 창신동 702");
        assertThat(ledger.getOwnerName()).isNull();
        assertThat(ledger.getBuildingArea()).isNull();
        assertThat(ledger.getTotalFloorArea()).isEqualByComparingTo("14544.66");
        assertThat(ledger.getExclusiveArea()).isNull();
        assertThat(ledger.getApprovalDate()).isEqualTo(LocalDate.of(1992, 11, 25));
        assertThat(ledger.getViolation()).isNull();
        assertThat(ledger.getDataSource()).isEqualTo(LedgerDataSource.BUILDING_HUB);
    }

    @Test
    @DisplayName("교체 저장: 그 사이 Mock 이 아니게 됐으면 손대지 않는다")
    void replaceMockSkipsNonMock() {
        BuildingLedger ledger = BuildingLedger.collect(PROPERTY_ID, "서울특별시 종로구 동망산길 19 (창신동)", null, "공동주택",
                null, null, new BigDecimal("100.00"), null, null, null, LedgerDataSource.BUILDING_HUB);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));

        assertThat(service.replaceMock(PROPERTY_ID, buildingHubDocument())).isFalse();
        assertThat(ledger.getTotalFloorArea()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("새 대장을 저장하면 같은 쓰기 트랜잭션에서 저장 직전에 재분석 대기를 세운다")
    void saveMarksReanalysisPending() {
        givenProperty();
        when(client.fetch(any())).thenReturn(Optional.of(document()));

        service.collectIfAbsent(PROPERTY_ID);

        InOrder order = inOrder(transactionManager, propertyRepository, buildingLedgerRepository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(propertyRepository).markReanalysisPending(PROPERTY_ID);
        order.verify(buildingLedgerRepository).saveAndFlush(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("대장을 저장하지 않는 경로(이미 있음 · 뗄 대장 없음 · 초당 한도)는 재분석 대기를 세우지 않는다")
    void noSaveDoesNotMarkReanalysisPending() {
        givenProperty();
        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(true);
        service.collectIfAbsent(PROPERTY_ID);

        when(buildingLedgerRepository.existsByPropertyId(PROPERTY_ID)).thenReturn(false);
        when(client.fetch(any())).thenReturn(Optional.empty());
        service.collectIfAbsent(PROPERTY_ID);

        when(client.fetch(any())).thenThrow(new BuildingLedgerRateLimitedException("시험"));
        service.collectIfAbsent(PROPERTY_ID);

        verify(propertyRepository, never()).markReanalysisPending(any());
    }

    @Test
    @DisplayName("교체 저장: Mock 행을 바꾸면 재분석 대기를 세운다")
    void replaceMockMarksReanalysisPending() {
        BuildingLedger ledger = BuildingLedger.collect(PROPERTY_ID, "서울특별시 시험구 시험로 1", "김임대", "공동주택",
                "철근콘크리트구조", new BigDecimal("600.30"), new BigDecimal("2550.00"), new BigDecimal("42.50"),
                LocalDate.of(1998, 4, 18), true, LedgerDataSource.MOCK);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));

        service.replaceMock(PROPERTY_ID, buildingHubDocument());

        verify(propertyRepository).markReanalysisPending(PROPERTY_ID);
    }

    @Test
    @DisplayName("교체 저장: Mock 이 아닌 행은 바꾸지 않으므로 재분석 대기도 세우지 않는다")
    void replaceMockOnNonMockDoesNotMarkReanalysisPending() {
        BuildingLedger ledger = BuildingLedger.collect(PROPERTY_ID, "서울특별시 종로구 동망산길 19 (창신동)", null, "공동주택",
                null, null, new BigDecimal("100.00"), null, null, null, LedgerDataSource.BUILDING_HUB);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));

        service.replaceMock(PROPERTY_ID, buildingHubDocument());

        verify(propertyRepository, never()).markReanalysisPending(any());
    }

    @Test
    @DisplayName("교체 저장: 대장 행이 없으면 재분석 대기를 세우지 않는다")
    void replaceMockWithoutLedgerDoesNotMarkReanalysisPending() {
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());

        service.replaceMock(PROPERTY_ID, buildingHubDocument());

        verify(propertyRepository, never()).markReanalysisPending(any());
    }

    private void givenStoredLedger(LedgerDataSource dataSource) {
        BuildingLedger ledger = mock(BuildingLedger.class);
        when(ledger.isMock()).thenReturn(dataSource == LedgerDataSource.MOCK);
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));
    }

    // ---------- 픽스처 ----------

    /** 매물 목을 등록하고 돌려준다. 조회 키는 기본 null 이다. */
    private PropertyStub givenProperty() {
        Property property = mock(Property.class);
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn("APARTMENT");
        when(property.getPropertyId()).thenReturn(PROPERTY_ID);
        when(property.naturalKey()).thenReturn(naturalKey());
        when(property.getLandlordName()).thenReturn("김임대");
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        return new PropertyStub(property);
    }

    private record PropertyStub(Property property) {
        void ledgerKey(LedgerLookupKey key) {
            when(property.ledgerKey()).thenReturn(key);
        }
    }

    /** 건축HUB 가 주는 꼴 — 지번 주소, 소유자 · 전용면적 · 위반건축물 없음. */
    private static BuildingLedgerDocument buildingHubDocument() {
        return new BuildingLedgerDocument("서울특별시 종로구 창신동 702", null, "공동주택", "철근콘크리트구조",
                null, new BigDecimal("14544.66"), null, LocalDate.of(1992, 11, 25), null, LedgerDataSource.BUILDING_HUB);
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
