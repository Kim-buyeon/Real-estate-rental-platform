package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MockBuildingLedgerClient} 검증. 결정성 · 대장의 형태 · 분포 셋을 본다.
 *
 * <p>분포는 표본 {@value #SAMPLE_SIZE}건에서 위반건축물 건수가 기대값 ± 이항분포 표준편차의 4배 안에 드는지로 본다.
 * Mock 은 결정적이라 흔들리지 않는다 — 섞기 함수가 한쪽으로 쏠려 설계한 비율과 크게 어긋나는 것을 잡으려는 것이다.
 */
class MockBuildingLedgerClientTest {

    private static final int SAMPLE_SIZE = 2_000;
    private static final double TOLERANCE_SIGMAS = 4.0;
    private static final int PERCENT = 100;
    private static final LocalDate REGISTRY_PRESERVATION_BASE_DATE = LocalDate.of(2005, 1, 1);

    private final MockBuildingLedgerClient client = new MockBuildingLedgerClient();

    @Test
    @DisplayName("같은 매물은 인스턴스가 달라도 항상 같은 대장이다")
    void sameLookupYieldsSameDocument() {
        BuildingLedgerLookup lookup = lookup(1024L, PropertyType.APARTMENT);

        assertThat(client.fetch(lookup)).isEqualTo(client.fetch(lookup));
        assertThat(new MockBuildingLedgerClient().fetch(lookup)).isEqualTo(client.fetch(lookup));
    }

    @Test
    @DisplayName("주용도는 아파트면 공동주택, 오피스텔이면 업무시설이다")
    void mainPurposeFollowsPropertyType() {
        assertThat(client.fetch(lookup(7L, PropertyType.APARTMENT)).buildingPurpose()).isEqualTo("공동주택");
        assertThat(client.fetch(lookup(7L, PropertyType.OFFICETEL)).buildingPurpose()).isEqualTo("업무시설");
    }

    @Test
    @DisplayName("주소 · 전용면적은 매물 그대로이고 건축면적 ≤ 연면적, 전용면적 ≤ 연면적이며 출처는 MOCK 이다")
    void areasAndAddressAreConsistent() {
        for (BuildingLedgerLookup lookup : sample()) {
            BuildingLedgerDocument document = client.fetch(lookup);

            assertThat(document.ledgerAddress()).isEqualTo(lookup.naturalKey().address());
            assertThat(document.exclusiveArea()).isEqualTo(lookup.naturalKey().areaSqm());
            assertThat(document.totalFloorArea()).isGreaterThanOrEqualTo(document.exclusiveArea());
            assertThat(document.buildingArea()).isPositive().isLessThanOrEqualTo(document.totalFloorArea());
            assertThat(document.totalFloorArea().scale()).isEqualTo(2);
            assertThat(document.buildingArea().scale()).isEqualTo(2);
            assertThat(document.dataSource()).isEqualTo(LedgerDataSource.MOCK);
        }
    }

    @Test
    @DisplayName("사용승인일은 1985년 이후이고 등기 Mock 의 소유권보존 기준일(2005-01-01)보다 앞선다")
    void approvalDatePrecedesRegistryPreservation() {
        for (BuildingLedgerLookup lookup : sample()) {
            LocalDate approvalDate = client.fetch(lookup).approvalDate();

            assertThat(approvalDate).isAfterOrEqualTo(MockBuildingLedgerClient.APPROVAL_BASE_DATE)
                    .isBefore(REGISTRY_PRESERVATION_BASE_DATE);
        }
    }

    @Test
    @DisplayName("대장 소유자는 임대인명 생성 쪽이 불일치로 정한 매물에서만 임대인과 다르다")
    void ownerDiffersOnlyWhenMismatchIsIntended() {
        int mismatches = 0;
        for (BuildingLedgerLookup lookup : sample()) {
            boolean intended = LandlordNameGenerator.isIntentionalMismatch(lookup.naturalKey());

            assertThat(client.fetch(lookup).ownerName().equals(lookup.landlordName())).isEqualTo(!intended);
            mismatches += intended ? 1 : 0;
        }
        assertThat(mismatches).isPositive();
    }

    @Test
    @DisplayName("위반건축물은 표본에서 설계 비율 근처로 나온다")
    void violationRateIsNearDesign() {
        long violations = sample().stream().filter(lookup -> client.fetch(lookup).violation()).count();

        double p = (double) MockBuildingLedgerClient.VIOLATION_PERCENT / PERCENT;
        double expected = SAMPLE_SIZE * p;
        double tolerance = TOLERANCE_SIGMAS * Math.sqrt(SAMPLE_SIZE * p * (1 - p));
        assertThat((double) violations).isBetween(expected - tolerance, expected + tolerance);
    }

    // ---------- 픽스처 ----------

    private static List<BuildingLedgerLookup> sample() {
        return LongStream.rangeClosed(1, SAMPLE_SIZE)
                .mapToObj(id -> lookup(id, id % 2 == 0 ? PropertyType.APARTMENT : PropertyType.OFFICETEL))
                .toList();
    }

    private static BuildingLedgerLookup lookup(long id, PropertyType type) {
        BigDecimal area = new BigDecimal("20.00").add(BigDecimal.valueOf(id % 120));
        PropertyNaturalKey key = new PropertyNaturalKey(
                "서울특별시 시험구 시험로 " + id, area, (int) (id % 20) + 1, 100_000_000L + id * 1_000_000L, 0L);
        return new BuildingLedgerLookup(id, key, LandlordNameGenerator.generate(key), type);
    }
}
