package com.duri.rentalplatform.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.SeoulDistrict;
import com.duri.rentalplatform.domain.property.vo.MarketPriceUpdate;
import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.property.vo.PropertyPriceSnapshot;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import com.duri.rentalplatform.domain.property.vo.PropertyRegistration;
import com.duri.rentalplatform.external.address.AddressNormalizeClient;
import com.duri.rentalplatform.external.address.Coordinates;
import com.duri.rentalplatform.external.address.GeocodeClient;
import com.duri.rentalplatform.external.address.NormalizedAddress;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import com.duri.rentalplatform.external.realestate.SaleBuildingType;
import com.duri.rentalplatform.external.realestate.SaleTransaction;
import com.duri.rentalplatform.external.realestate.SaleTransactionClient;
import com.duri.rentalplatform.external.realestate.SaleTransactionQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PropertyLoadService} 검증. 외부 의존 다섯(전월세 실거래가 · 매매 실거래가 · 주소 정규화 · 좌표 · 저장)이 모두
 * 인터페이스이거나 주입되는 빈이라 Mockito 스텁으로 대체한다 — 데이터베이스도 Testcontainers도
 * 필요 없다.
 *
 * <p><b>시세 표본</b> — 매물은 전월세에서, 시세는 같은 유형의 매매에서 온다. 기본 스텁은 어느 자치구든 아파트 매매 두 건
 * (59.90㎡ · 30.00㎡, 각 {@link #SALE_PRICE})과 오피스텔 매매 한 건(30.00㎡, {@link #OFFICETEL_SALE_PRICE})을 돌려준다.
 * 그래서 아파트는 두 면적대, 오피스텔은 30.00㎡ 만 시세가 잡히고, 나머지(예: 150㎡ 아파트, 59.90㎡ 오피스텔)는 시세없음으로
 * 빠진다. 시세가 매물의 보증금(3억)이 아니라 매매가(5억)인지가 곧 「매매 표본을 쓰는가」의 확인이다.
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

    /** 기본 매매 표본의 거래금액 · 계약일. 매물 보증금(3억)과 다른 값이라 시세가 어느 쪽에서 왔는지 드러난다. */
    private static final long SALE_PRICE = 500_000_000L;
    private static final LocalDate SALE_DATE = TARGET_MONTH.atDay(20);

    /** 기본 오피스텔 매매 표본의 거래금액. 아파트 매매가와 달라 유형이 섞였는지 드러난다. */
    private static final long OFFICETEL_SALE_PRICE = 200_000_000L;

    private RentTransactionClient rentTransactionClient;
    private SaleTransactionClient saleTransactionClient;
    private AddressNormalizeClient addressNormalizeClient;
    private GeocodeClient geocodeClient;
    private PropertyLoadWriter propertyLoadWriter;
    private PropertyLoadService service;

    @BeforeEach
    void setUp() {
        rentTransactionClient = mock(RentTransactionClient.class);
        saleTransactionClient = mock(SaleTransactionClient.class);
        addressNormalizeClient = mock(AddressNormalizeClient.class);
        geocodeClient = mock(GeocodeClient.class);
        propertyLoadWriter = mock(PropertyLoadWriter.class);
        service = new PropertyLoadService(rentTransactionClient, saleTransactionClient,
                addressNormalizeClient, geocodeClient, propertyLoadWriter);

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
                .thenAnswer(invocation -> idsFor(invocation.getArgument(0)));
        // 어느 자치구든 아파트 매매는 두 면적대, 오피스텔 매매는 30.00㎡ 한 면적대의 표본을 준다.
        when(saleTransactionClient.findSaleTransactions(any())).thenAnswer(invocation -> {
            SaleTransactionQuery query = invocation.getArgument(0);
            // null 은 테스트 안에서 any() 로 이 스텁을 덮어쓸 때 Mockito 가 이 답을 한 번 부르며 넘기는 값이다.
            if (query == null) {
                return List.of();
            }
            if (query.buildingType() == SaleBuildingType.OFFICETEL) {
                return List.of(officetelSale(query.lawdCode(), new BigDecimal("30.00"), OFFICETEL_SALE_PRICE));
            }
            return List.of(
                    sale(query.lawdCode(), new BigDecimal("59.90"), SALE_PRICE, false),
                    sale(query.lawdCode(), new BigDecimal("30.00"), SALE_PRICE, false));
        });
        // findLoadedNaturalKeys · findRentTransactions 는 Mockito 기본값이 이미 빈 Set · 빈 List라
        // 스텁하지 않은 자치구 · 서비스구분은 자동으로 0건 처리된다.
    }

    private static SaleTransaction sale(String lawdCode, BigDecimal areaSqm, long dealAmount, boolean cancelled) {
        return new SaleTransaction(lawdCode, "역삼동", "매매아파트", "300-3", areaSqm, 7, dealAmount, SALE_DATE,
                2005, cancelled, SaleBuildingType.APARTMENT, SaleBuildingType.APARTMENT.getDataSource());
    }

    private static SaleTransaction officetelSale(String lawdCode, BigDecimal areaSqm, long dealAmount) {
        return new SaleTransaction(lawdCode, "역삼동", "매매오피스텔", "300-4", areaSqm, 9, dealAmount, SALE_DATE,
                2012, false, SaleBuildingType.OFFICETEL, SaleBuildingType.OFFICETEL.getDataSource());
    }

    private RentTransaction officetelTransaction(String jibun, BigDecimal areaSqm, int floor) {
        return new RentTransaction(
                PRIMARY.getLawdCode(), "역삼동", "테스트오피스텔", jibun,
                areaSqm, floor, 150_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2012, RentBuildingType.OFFICETEL, RentBuildingType.OFFICETEL.getDataSource());
    }

    /** 저장 스텁의 반환값 — 저장한 건수만큼 식별자를 지어 준다. */
    private static List<Long> idsFor(List<?> registrations) {
        return LongStream.rangeClosed(1, registrations.size()).boxed().toList();
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
        // 시세는 보증금(3억)이 아니라 같은 법정동 · 면적대 매매 표본(5억 한 건)의 중앙값이고, 기준일은 그 매매의 계약일이다.
        assertThat(saved.marketPrice()).isEqualTo(SALE_PRICE);
        assertThat(saved.priceType()).isEqualTo(PriceType.ACTUAL_TRANSACTION);
        assertThat(saved.priceDate()).isEqualTo(SALE_DATE);
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
    @DisplayName("같은 법정동·자치구 어디에도 같은 면적대의 매매 표본이 없으면 시세를 산출하지 못해 건너뛴다")
    void skipsATransactionWithNoMarketPriceSample() {
        // 40㎡ 미만 · 역삼동 — 기본 매매 표본(30.00㎡)이 있어 시세가 잡힌다.
        RentTransaction withSample = validTransaction("100-1", new BigDecimal("30.00"), 1);
        // 135㎡ 이상 — 이 면적대의 매매 표본은 법정동에도 자치구에도 없다. 전세 거래 자신은 표본이 되지 않는다.
        RentTransaction withoutSample = validTransaction("999-9", new BigDecimal("150.00"), 2);
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(withSample, withoutSample));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedMarketPriceNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isEqualTo(1);
    }

    @Test
    @DisplayName("법정동 매매 표본이 없으면 같은 자치구 · 같은 면적대의 매매 표본으로 넓혀 시세를 잡는다")
    void widensToDistrictSaleSample() {
        // 논현동에는 매매 표본이 없고 같은 자치구의 역삼동(기본 표본)에만 있다.
        RentTransaction otherDong = new RentTransaction(
                PRIMARY.getLawdCode(), "논현동", "테스트아파트", "400-4",
                new BigDecimal("59.90"), 2, 300_000_000L, 0L,
                TARGET_MONTH.atDay(10), 2005, APARTMENT, APARTMENT.getDataSource());
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(otherDong));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropertyRegistration>> captor = ArgumentCaptor.forClass(List.class);
        verify(propertyLoadWriter).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).marketPrice()).isEqualTo(SALE_PRICE);
        assertThat(report.getSkippedMarketPriceNotFound()).isZero();
    }

    @Test
    @DisplayName("같은 동 · 같은 면적대라도 아파트 매물은 아파트 매매, 오피스텔 매물은 오피스텔 매매의 중앙값을 시세로 쓴다")
    void usesSaleSampleOfTheSamePropertyType() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("30.00"), 1)));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, RentBuildingType.OFFICETEL)))
                .thenReturn(List.of(officetelTransaction("500-5", new BigDecimal("30.00"), 4)));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropertyRegistration>> captor = ArgumentCaptor.forClass(List.class);
        verify(propertyLoadWriter).saveAll(captor.capture());
        Map<PropertyType, Long> priceByType = new EnumMap<>(PropertyType.class);
        captor.getValue().forEach(saved -> priceByType.put(saved.propertyType(), saved.marketPrice()));
        // 유형이 섞였다면 30.00㎡ 표본 세 건(2억 · 5억 · 5억)의 중앙값 5억이 둘 다에 나온다.
        assertThat(priceByType).containsExactlyInAnyOrderEntriesOf(Map.of(
                PropertyType.APARTMENT, SALE_PRICE,
                PropertyType.OFFICETEL, OFFICETEL_SALE_PRICE));
    }

    @Test
    @DisplayName("같은 유형의 매매 표본이 없으면 다른 유형 표본이 있어도 시세를 산출하지 못해 건너뛴다")
    void doesNotBorrowAnotherTypeSaleSample() {
        // 59.90㎡ 매매 표본은 아파트뿐이다. 오피스텔 매물은 이것을 쓰지 않는다.
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, RentBuildingType.OFFICETEL)))
                .thenReturn(List.of(officetelTransaction("500-5", new BigDecimal("59.90"), 4)));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedMarketPriceNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isZero();
        verify(propertyLoadWriter, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("매매 표본이 해제 거래뿐이면 시세를 산출하지 못해 건너뛴다")
    void skipsWhenOnlyCancelledSalesExist() {
        when(saleTransactionClient.findSaleTransactions(any())).thenReturn(List.of(
                sale(PRIMARY.getLawdCode(), new BigDecimal("59.90"), SALE_PRICE, true)));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("59.90"), 1)));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getSkippedMarketPriceNotFound()).isEqualTo(1);
        assertThat(report.getSaved()).isZero();
        verify(propertyLoadWriter, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("매매는 전월세와 같은 기간(달 목록)으로 유형마다 조회한다")
    void fetchesSalesForTheSameMonthsAsRents() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("59.90"), 1)));

        service.load(2, new PropertyLoadReport());

        for (YearMonth month : List.of(TARGET_MONTH, TARGET_MONTH.minusMonths(1))) {
            for (SaleBuildingType type : SaleBuildingType.values()) {
                verify(saleTransactionClient).findSaleTransactions(
                        new SaleTransactionQuery(PRIMARY.getLawdCode(), month, type));
            }
        }
    }

    @Test
    @DisplayName("쓸 수 있는 전월세 거래가 없는 자치구는 매매를 조회하지 않는다")
    void doesNotFetchSalesWhenThereIsNoRentCandidate() {
        // 전월세 스텁이 없으면 모든 자치구가 0건이다.
        service.load(1, new PropertyLoadReport());

        verify(saleTransactionClient, never()).findSaleTransactions(any());
    }

    @Test
    @DisplayName("매매 조회 한 건이 실패해도 연동 실패로 기록하고 나머지 표본으로 시세를 잡아 저장한다")
    void recordsSaleFetchFailureAndContinues() {
        when(saleTransactionClient.findSaleTransactions(
                new SaleTransactionQuery(PRIMARY.getLawdCode(), TARGET_MONTH, SaleBuildingType.OFFICETEL)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("59.90"), 1)));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        assertThat(report.getFailedExternal()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("매매 실거래가 조회 실패")
                && reason.contains(PRIMARY.getDistrictName()));
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
            return idsFor(registrations);
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
        assertThat(captor.getValue().get(0).marketPrice()).isEqualTo(OFFICETEL_SALE_PRICE);
    }

    // ---------- 갱신 적재(RISK-08) ----------

    private static final String NORMALIZED_ADDRESS = "정규화-서울특별시 강남구 역삼동 100-1";
    private static final long STORED_ID = 77L;

    /** 기본 거래(100-1 · 59.90㎡ · 3층 · 3억 전세)의 자연키와 저장된 시세. 새 시세는 기본 매매 표본의 5억 · 기준일은 그 매매의 계약일이다. */
    private void storedPrice(Long marketPrice, LocalDate priceDate) {
        PropertyNaturalKey key = new PropertyNaturalKey(NORMALIZED_ADDRESS, new BigDecimal("59.90"), 3, 300_000_000L, 0L);
        when(propertyLoadWriter.findLoadedPrices(PRIMARY.getDistrictName())).thenReturn(Map.of(key,
                new PropertyPriceSnapshot(STORED_ID, marketPrice, PriceType.ACTUAL_TRANSACTION, priceDate)));
    }

    private void fetchesDefaultTransaction() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT)))
                .thenReturn(List.of(validTransaction("100-1", new BigDecimal("59.90"), 3)));
    }

    @Test
    @DisplayName("갱신: 새 매물은 저장하고 그 식별자를 신규로 돌려준다 — 시세 갱신은 없다")
    void refreshReturnsNewPropertyIds() {
        fetchesDefaultTransaction();

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        assertThat(result.newPropertyIds()).containsExactly(1L);
        assertThat(result.priceChangedPropertyIds()).isEmpty();
        assertThat(report.getSaved()).isEqualTo(1);
        verify(propertyLoadWriter, never()).updateMarketPrices(anyList());
    }

    @Test
    @DisplayName("갱신: 기존 매물의 시세 · 기준일이 저장값과 같으면 손대지 않는다 — 값이 동일하면 재분석하지 않는다")
    void refreshLeavesSamePriceUntouched() {
        fetchesDefaultTransaction();
        storedPrice(SALE_PRICE, SALE_DATE);

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        verify(propertyLoadWriter, never()).updateMarketPrices(anyList());
        verify(propertyLoadWriter, never()).saveAll(anyList());
        assertThat(result.newPropertyIds()).isEmpty();
        assertThat(result.priceChangedPropertyIds()).isEmpty();
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
        assertThat(report.getPriceUpdated()).isZero();
    }

    @Test
    @DisplayName("갱신: 기존 매물의 시세가 다르면 새 시세로 갱신을 넘기고, 금액이 바뀐 매물을 시세 변경으로 돌려준다")
    void refreshUpdatesChangedPrice() {
        fetchesDefaultTransaction();
        storedPrice(280_000_000L, TARGET_MONTH.atDay(10));
        when(propertyLoadWriter.updateMarketPrices(anyList())).thenReturn(List.of(STORED_ID));

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        verify(propertyLoadWriter).updateMarketPrices(List.of(new MarketPriceUpdate(
                STORED_ID, SALE_PRICE, PriceType.ACTUAL_TRANSACTION, SALE_DATE)));
        verify(propertyLoadWriter, never()).saveAll(anyList());
        assertThat(result.priceChangedPropertyIds()).containsExactly(STORED_ID);
        assertThat(report.getPriceUpdated()).isEqualTo(1);
        assertThat(report.getPriceChanged()).isEqualTo(1);
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신: 기준일만 다르면 저장값 갱신은 넘기되, 쓰기 서비스가 금액 변경으로 돌려주지 않으면 시세 변경에 넣지 않는다")
    void refreshUpdatesDateOnlyWithoutMarkingPriceChanged() {
        fetchesDefaultTransaction();
        storedPrice(SALE_PRICE, TARGET_MONTH.atDay(1));
        when(propertyLoadWriter.updateMarketPrices(anyList())).thenReturn(List.of());

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        verify(propertyLoadWriter).updateMarketPrices(anyList());
        assertThat(result.priceChangedPropertyIds()).isEmpty();
        assertThat(report.getPriceUpdated()).isEqualTo(1);
        assertThat(report.getPriceChanged()).isZero();
    }

    @Test
    @DisplayName("갱신: 같은 자연키가 한 회차에 두 번 나와도 시세 비교 · 갱신은 한 번뿐이다")
    void refreshComparesEachExistingPropertyOnce() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT))).thenReturn(List.of(
                validTransaction("100-1", new BigDecimal("59.90"), 3),
                validTransaction("100-1", new BigDecimal("59.90"), 3)));
        storedPrice(280_000_000L, TARGET_MONTH.atDay(10));
        when(propertyLoadWriter.updateMarketPrices(anyList())).thenReturn(List.of(STORED_ID));

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        verify(propertyLoadWriter).updateMarketPrices(argThat(updates -> updates.size() == 1));
        assertThat(result.priceChangedPropertyIds()).containsExactly(STORED_ID);
        assertThat(report.getSkippedDuplicate()).isEqualTo(2);
    }

    @Test
    @DisplayName("갱신: 자치구의 실거래가 수집이 일부 실패하면 기존 매물 시세는 갱신하지 않고, 새 매물은 저장한다")
    void refreshSkipsPriceUpdatesWhenFetchIsIncomplete() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT))).thenReturn(List.of(
                validTransaction("100-1", new BigDecimal("59.90"), 3),
                validTransaction("200-2", new BigDecimal("59.90"), 5)));
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, RentBuildingType.OFFICETEL)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        storedPrice(280_000_000L, TARGET_MONTH.atDay(10));

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        verify(propertyLoadWriter, never()).updateMarketPrices(anyList());
        assertThat(result.priceChangedPropertyIds()).isEmpty();
        // 200-2 는 새 매물이라 초기 적재와 같이 저장한다. 100-1 은 기존 매물이라 저장하지 않는다.
        assertThat(result.newPropertyIds()).containsExactly(1L);
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
        assertThat(report.getFailedExternal()).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신: 자치구의 매매 수집이 일부 실패하면 기존 매물 시세는 갱신하지 않고, 새 매물은 저장한다")
    void refreshSkipsPriceUpdatesWhenSaleFetchIsIncomplete() {
        when(rentTransactionClient.findRentTransactions(queryOf(PRIMARY, APARTMENT))).thenReturn(List.of(
                validTransaction("100-1", new BigDecimal("59.90"), 3),
                validTransaction("200-2", new BigDecimal("59.90"), 5)));
        when(saleTransactionClient.findSaleTransactions(
                new SaleTransactionQuery(PRIMARY.getLawdCode(), TARGET_MONTH, SaleBuildingType.OFFICETEL)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        storedPrice(280_000_000L, TARGET_MONTH.atDay(10));

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        // 덜 모은 매매 표본의 시세(5억)로 저장된 시세(2억8천)를 덮지 않는다.
        verify(propertyLoadWriter, never()).updateMarketPrices(anyList());
        assertThat(result.priceChangedPropertyIds()).isEmpty();
        assertThat(result.newPropertyIds()).containsExactly(1L);
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
        assertThat(report.getFailedExternal()).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신: 시세 갱신 덩어리가 실패하면 기록하고 시세 변경으로 넘기지 않는다")
    void refreshRecordsPriceUpdateFailure() {
        fetchesDefaultTransaction();
        storedPrice(280_000_000L, TARGET_MONTH.atDay(10));
        when(propertyLoadWriter.updateMarketPrices(anyList())).thenThrow(new RuntimeException("DB 커넥션 끊김"));

        PropertyLoadReport report = new PropertyLoadReport();
        PropertyRefreshResult result = service.refresh(1, report);

        assertThat(result.priceChangedPropertyIds()).isEmpty();
        assertThat(report.getFailedUnexpected()).isEqualTo(1);
        assertThat(report.getFailures()).anyMatch(reason -> reason.contains("시세 갱신 실패"));
        assertThat(report.getPriceUpdated()).isZero();
    }

    @Test
    @DisplayName("초기 적재는 기존 매물의 시세를 읽지도 갱신하지도 않는다 — 기존 동작 유지")
    void initialLoadNeverUpdatesPrices() {
        fetchesDefaultTransaction();
        PropertyNaturalKey key = new PropertyNaturalKey(NORMALIZED_ADDRESS, new BigDecimal("59.90"), 3, 300_000_000L, 0L);
        when(propertyLoadWriter.findLoadedNaturalKeys(PRIMARY.getDistrictName())).thenReturn(Set.of(key));

        PropertyLoadReport report = new PropertyLoadReport();
        service.load(1, report);

        verify(propertyLoadWriter, never()).findLoadedPrices(anyString());
        verify(propertyLoadWriter, never()).updateMarketPrices(anyList());
        assertThat(report.getSkippedDuplicate()).isEqualTo(1);
    }
}
