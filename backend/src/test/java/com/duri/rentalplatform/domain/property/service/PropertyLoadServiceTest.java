package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.SeoulDistrict;
import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.Coordinates;
import com.duri.rentalplatform.external.address.GeocodeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PropertyLoadService} 검증. 외부 의존 넷(실거래가 · 주소 정규화 · 좌표 · 저장)이 모두
 * 인터페이스이거나 주입되는 빈이라 Mockito 스텁으로 대체한다 — 데이터베이스도 Testcontainers도
 * 필요 없다.
 *
 * <p><b>근거</b> — 데이터 적재 설계서 1.4. 특히 클래스 Javadoc이 적은 <b>네 층의 실패 격리</b>가
 * 실제로 도는지를 핵심으로 본다.
 * <ol>
 *   <li>필수 값 사전 검사(면적 · 보증금 · 계약일)</li>
 *   <li>건별 변환 — 예상하지 못한 예외까지 잡아 그 건만 버린다</li>
 *   <li>저장 덩어리 실패 — 그 덩어리만 버리고 다음으로</li>
 *   <li>자치구 단위 실패 — 그 구만 버리고 다음 구로</li>
 * </ol>
 *
 * <p><b>덮지 않는 것</b> — {@code property.registered_at}(적재 시점)은 JPA 감사 리스너
 * (@CreatedDate)가 채우며 이 서비스 코드는 그 값을 다루지 않는다. 감사 컬럼 채움은 영속성 컨텍스트가
 * 있어야 확인되므로 이 단위 테스트의 범위 밖이다.
 */
class PropertyLoadServiceTest {

    private static final SeoulDistrict PRIMARY = SeoulDistrict.GANGNAM;
    private static final SeoulDistrict SECONDARY = SeoulDistrict.SEOCHO;
    private static final YearMonth TARGET_MONTH = YearMonth.now().minusMonths(1);
    private static final RentBuildingType APARTMENT = RentBuildingType.APARTMENT;

    private RentTransactionClient rentTransactionClient;
    private AddressNormalizeClient addressNormalizeClient;
    private GeocodeClient geocodeClient;
    private PropertyLoadWriter propertyLoadWriter;
    private PropertyLoadService service;

    @BeforeEach
    void setUp() {
        rentTransactionClient = mock(RentTransactionClient.class);
        addressNormalizeClient = mock(AddressNormalizeClient.class);
        geocodeClient = mock(GeocodeClient.class);
        propertyLoadWriter = mock(PropertyLoadWriter.class);
        service = new PropertyLoadService(
                rentTransactionClient, addressNormalizeClient, geocodeClient, propertyLoadWriter);

        // 기본은 전부 정상 — 실패·건너뜀을 보는 테스트만 아래에서 더 좁은 매처로 덮어쓴다.
        // 더 좁은 매처를 테스트 메서드 안에서(= 이후에) 등록하면 Mockito가 그 매처를 우선한다.
        when(addressNormalizeClient.normalize(anyString())).thenAnswer(invocation -> {
            String rawAddress = invocation.getArgument(0);
            return Optional.of(new NormalizedAddress(
                    "정규화-" + rawAddress, rawAddress, PRIMARY.getDistrictName(), "역삼동", null, "TEST_JUSO"));
        });
        when(geocodeClient.geocode(anyString())).thenReturn(Optional.of(new Coordinates(
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), "TEST_GEO")));
        when(propertyLoadWriter.saveAll(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        // findLoadedNaturalKeys · findRentTransactions 는 Mockito 기본값이 이미 빈 Set · 빈 List라
        // 스텁하지 않은 자치구 · 서비스구분은 자동으로 0건 처리된다.
    }

    private RentTransactionQuery queryOf(SeoulDistrict district, RentBuildingType buildingType) {
        return new RentTransactionQuery(district.getLawdCode(), TARGET_MONTH, buildingType);
    }

    /** 모든 필수 값을 갖춘 유효한 실거래 한 건. 지번만 바꿔 가며 서로 다른 매물을 만든다. */
    private RentTransaction validTransaction(String jibun, BigDecimal areaSqm, int floor) {
        return new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트아파트", jibun,
                areaSqm, floor, 300_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
    }

    @Test
    @DisplayName("정상 거래 한 건은 정규화된 주소·좌표·시세를 갖춘 매물로 저장된다")
    void loadsAndSavesANormalTransaction() {
        RentTransaction transaction = validTransaction("100-1", new BigDecimal("59.90"), 3);
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(transaction));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFetched()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropertyRegistration>> captor = ArgumentCaptor.forClass(List.class);
        verify(propertyLoadWriter).saveAll(captor.capture());
        PropertyRegistration saved = captor.getValue().get(0);

        // 정규화 · 좌표 확보 후 저장한다 — 원문 주소가 아니라 정규화된 주소가 저장값이다.
        assertThat(saved.address()).isEqualTo("정규화-서울특별시 강남구 역삼동 100-1");
        assertThat(saved.district()).isEqualTo("강남구");
        assertThat(saved.contractType()).isEqualTo(ContractType.DEPOSIT_ONLY);
        assertThat(saved.propertyType()).isEqualTo(PropertyType.APARTMENT);
        assertThat(saved.deposit()).isEqualTo(300_000_000L);
        assertThat(saved.monthlyRent()).isEqualTo(0L);
        // 표본이 이 거래 하나뿐이라 중앙값은 그 보증금 자신이다.
        assertThat(saved.marketPrice()).isEqualTo(300_000_000L);
        assertThat(saved.priceDate()).isEqualTo(TARGET_MONTH.atDay(10));
        assertThat(saved.floor()).isEqualTo(3);
        assertThat(saved.builtYear()).isEqualTo(2005);
        assertThat(saved.latitude()).isEqualByComparingTo("37.5000000");
        assertThat(saved.longitude()).isEqualByComparingTo("127.0000000");
        assertThat(saved.landlordName()).isEqualTo(LandlordNameGenerator.generate(saved.naturalKey()));
    }

    @Test
    @DisplayName("이미 적재된 자연키와 같은 거래는 재실행에서 건너뛰어 저장되지 않는다 — 재실행 멱등성")
    void skipsATransactionThatMatchesAnAlreadyLoadedNaturalKey() {
        RentTransaction transaction = validTransaction("100-1", new BigDecimal("59.90"), 3);
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(transaction));

        String normalizedAddress = "정규화-서울특별시 강남구 역삼동 100-1";
        PropertyNaturalKey alreadyLoadedKey =
                new PropertyNaturalKey(normalizedAddress, new BigDecimal("59.90"), 3, 300_000_000L, 0L);
        when(propertyLoadWriter.findLoadedNaturalKeys(PRIMARY.getDistrictName()))
                .thenReturn(Set.of(alreadyLoadedKey));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(0);
        verify(propertyLoadWriter, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("같은 자연키가 저장 덩어리(500건) 경계를 가로질러 나와도 중복 적재를 막는다")
    void blocksADuplicateThatCrossesTheSaveChunkBoundary() {
        // PropertyLoadService.SAVE_CHUNK_SIZE 는 비공개다. 이 테스트는 그 경계 자체를 검증 대상으로
        // 삼으므로 같은 값(500)을 직접 겨냥한다 — 기준 테이블 값이 아니라 구현의 저장 배치 크기다.
        int chunkSize = 500;
        List<RentTransaction> transactions = new ArrayList<>();
        for (int floor = 1; floor <= chunkSize; floor++) {
            transactions.add(validTransaction("100-1", new BigDecimal("59.90"), floor));
        }
        // 501번째 건은 첫 건(floor=1)과 자연키가 같다. 첫 청크(500건)가 이미 저장을 마친 뒤에 나온다.
        transactions.add(validTransaction("100-1", new BigDecimal("59.90"), 1));

        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(transactions);

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFetched()).isEqualTo(chunkSize + 1);
        assertThat(report.getSaved()).isEqualTo(chunkSize);
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
        verify(propertyLoadWriter, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("전용면적이 없는 거래는 사전 검사에서 걸러지고 나머지는 저장된다")
    void skipsATransactionMissingAreaSqm() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction missingArea = new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트아파트", "200-2",
                null, 2, 300_000_000L, 0L, TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, missingArea));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedInvalidData()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("전용면적"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("보증금이 없는 거래는 사전 검사에서 걸러지고 나머지는 저장된다")
    void skipsATransactionMissingDeposit() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction missingDeposit = new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트아파트", "200-2",
                new BigDecimal("59.90"), 2, null, 0L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, missingDeposit));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedInvalidData()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("보증금"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("계약일이 없는 거래는 사전 검사에서 걸러지고 나머지는 저장된다")
    void skipsATransactionMissingContractDate() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction missingContractDate = new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트아파트", "200-2",
                new BigDecimal("59.90"), 2, 300_000_000L, 0L,
                null, 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, missingContractDate));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedInvalidData()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("계약일"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("정규화 결과가 없는 주소는 건너뛰고 나머지는 저장된다")
    void skipsATransactionWhoseAddressCannotBeNormalized() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction unmatched = validTransaction("999-9", new BigDecimal("59.90"), 2);
        when(addressNormalizeClient.normalize(argThat(raw -> raw != null && raw.contains("999-9"))))
                .thenReturn(Optional.empty());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, unmatched));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedAddressNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("주소 정규화 연동이 응답하지 못하면 연동 실패로 기록하고 나머지는 저장된다")
    void recordsExternalFailureWhenAddressNormalizationFails() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction failing = validTransaction("999-9", new BigDecimal("59.90"), 2);
        when(addressNormalizeClient.normalize(argThat(raw -> raw != null && raw.contains("999-9"))))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, failing));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedExternal()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("주소 정규화 실패"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("좌표를 찾지 못한 주소는 건너뛰고 나머지는 저장된다")
    void skipsATransactionWhoseCoordinatesCannotBeFound() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction noCoordinates = validTransaction("999-9", new BigDecimal("59.90"), 2);
        when(geocodeClient.geocode(argThat(addr -> addr != null && addr.contains("999-9"))))
                .thenReturn(Optional.empty());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, noCoordinates));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedCoordinatesNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("좌표 연동이 응답하지 못하면 연동 실패로 기록하고 나머지는 저장된다")
    void recordsExternalFailureWhenGeocodingFails() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction failing = validTransaction("999-9", new BigDecimal("59.90"), 2);
        when(geocodeClient.geocode(argThat(addr -> addr != null && addr.contains("999-9"))))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, failing));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedExternal()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("좌표 조회 실패"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 법정동·면적대에 전세 표본이 없으면 시세를 산출하지 못해 건너뛴다")
    void skipsATransactionWithNoMarketPriceSample() {
        // UNDER_40 · 역삼동 · 전세 — 자기 자신이 표본이 되어 시세가 잡힌다.
        RentTransaction leaseWithSample = validTransaction("100-1", new BigDecimal("30.00"), 1);
        // OVER_135 · 논현동 · 월세라 표본(전세만 집계)에서 제외되고, 다른 표본도 없다.
        RentTransaction monthlyWithoutSample = new RentTransaction(
                PRIMARY.getLawdCode(), "논현동", "테스트아파트", "999-9",
                new BigDecimal("150.00"), 2, 50_000_000L, 1_000_000L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(leaseWithSample, monthlyWithoutSample));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedMarketPriceNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 건 변환 중 예상 못한 예외가 나도 그 건만 버리고 나머지는 저장된다")
    void skipsOnlyTheRecordThatThrowsAnUnexpectedException() {
        RentTransaction good = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction broken = validTransaction("999-9", new BigDecimal("59.90"), 2);
        // BusinessException 이 아닌 순수 RuntimeException — 코딩 버그를 흉내낸다.
        when(addressNormalizeClient.normalize(argThat(raw -> raw != null && raw.contains("999-9"))))
                .thenThrow(new IllegalStateException("예기치 못한 버그"));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(good, broken));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedInvalidData()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("매물 변환 실패"));
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장 덩어리가 실패해도 그 사실을 기록하고 나머지 자치구는 계속 적재된다")
    void recordsSaveChunkFailureAndContinuesWithOtherDistricts() {
        RentTransaction primaryTx = validTransaction("100-1", new BigDecimal("59.90"), 1);
        RentTransaction secondaryTx = new RentTransaction(
                SECONDARY.getLawdCode(), "서초동", "테스트아파트", "100-1",
                new BigDecimal("59.90"), 1, 300_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(primaryTx));
        when(rentTransactionClient.findRentTransactions(queryOf(SECONDARY, APARTMENT)))
                .thenReturn(List.of(secondaryTx));

        // 이후 등록한 stub 이 우선하므로, setUp 의 기본 saveAll 응답을 이 테스트 안에서 완전히 대체한다.
        when(propertyLoadWriter.saveAll(anyList())).thenAnswer(invocation -> {
            List<?> registrations = invocation.getArgument(0);
            boolean isPrimaryChunk = registrations.stream()
                    .anyMatch(reg -> ((PropertyRegistration) reg).district().equals(PRIMARY.getDistrictName()));
            if (isPrimaryChunk) {
                throw new RuntimeException("DB 커넥션 끊김");
            }
            return registrations.size();
        });

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedUnexpected()).isEqualTo(1);
        assertThat(report.getFailures())
                .anyMatch(reason -> reason.contains("저장 실패") && reason.contains(PRIMARY.getDistrictName()));
        // GANGNAM 의 청크는 버려지고 SEOCHO 만 저장된다 — 한 덩어리의 실패가 다른 자치구를 막지 않는다.
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 자치구에서 예상 못한 예외가 나도 기록하고 나머지 자치구는 계속 적재된다")
    void recordsDistrictLevelFailureAndContinuesWithOtherDistricts() {
        RentTransaction secondaryTx = new RentTransaction(
                SECONDARY.getLawdCode(), "서초동", "테스트아파트", "100-1",
                new BigDecimal("59.90"), 1, 300_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("59.90"), 1)));
        when(rentTransactionClient.findRentTransactions(queryOf(SECONDARY, APARTMENT)))
                .thenReturn(List.of(secondaryTx));
        when(propertyLoadWriter.findLoadedNaturalKeys(PRIMARY.getDistrictName()))
                .thenThrow(new RuntimeException("커넥션 풀 고갈"));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedUnexpected()).isEqualTo(1);
        assertThat(report.getFailures())
                .anyMatch(reason -> reason.contains("자치구 적재 중단") && reason.contains(PRIMARY.getDistrictName()));
        // GANGNAM 은 통째로 버려지고 SEOCHO 만 저장된다.
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 서비스 구분의 조회가 실패해도 다른 서비스 구분은 계속 적재된다")
    void continuesWithOtherBuildingTypesWhenOneFetchFails() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        RentTransaction officetelTx = new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트오피스텔", "100-1",
                new BigDecimal("30.00"), 5, 200_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2010, RentBuildingType.OFFICETEL, RentBuildingType.OFFICETEL.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, RentBuildingType.OFFICETEL)))
                .thenReturn(List.of(officetelTx));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedExternal()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("실거래가 조회 실패"));
        assertThat(report.getSaved()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropertyRegistration>> captor = ArgumentCaptor.forClass(List.class);
        verify(propertyLoadWriter).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).propertyType()).isEqualTo(PropertyType.OFFICETEL);
    }
}
