package com.duri.rentalplatform.external.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.calculator.LandlordNameGenerator;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import com.duri.rentalplatform.domain.risk.enums.MortgageRightType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.external.registry.RegistryDocument.MortgageEntry;
import com.duri.rentalplatform.external.registry.RegistryDocument.OwnershipEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MockRegistryClient} 검증. 결정성 · 등기의 형태 · 분포 셋을 본다.
 *
 * <p>분포는 표본 {@value #SAMPLE_SIZE}건에서 각 항목의 건수가 기대값 ± 이항분포 표준편차의 4배 안에 드는지로 본다.
 * Mock 은 결정적이라 흔들리지 않는다 — 이 허용폭은 우연을 견디려는 것이 아니라, 섞기 함수가 한쪽으로 쏠려
 * 설계한 비율과 크게 어긋나는 것을 잡으려는 것이다.
 */
class MockRegistryClientTest {

    private static final int SAMPLE_SIZE = 2_000;
    private static final long MARKET_PRICE = 345_670_000L;
    private static final double TOLERANCE_SIGMAS = 4.0;
    private static final int PERCENT = 100;

    private final MockRegistryClient client = new MockRegistryClient();

    @Test
    @DisplayName("같은 매물은 인스턴스가 달라도 항상 같은 등기다")
    void sameLookupYieldsSameDocument() {
        RegistryLookup lookup = lookup(1024L, PropertyType.APARTMENT);

        assertThat(client.fetch(lookup)).isEqualTo(client.fetch(lookup));
        assertThat(new MockRegistryClient().fetch(lookup)).isEqualTo(client.fetch(lookup));
    }

    @Test
    @DisplayName("현재 소유자는 임대인명 생성 쪽이 불일치로 정한 매물에서만 임대인과 다르다")
    void currentOwnerDiffersOnlyWhenMismatchIsIntended() {
        int mismatches = 0;
        for (RegistryLookup lookup : sample()) {
            List<OwnershipEntry> activeOwners = client.fetch(lookup).ownerships().stream()
                    .filter(isOwnershipChange().and(OwnershipEntry::isActive))
                    .toList();
            boolean intended = LandlordNameGenerator.isIntentionalMismatch(lookup.naturalKey());

            assertThat(activeOwners).hasSize(1);
            assertThat(activeOwners.get(0).holderName().equals(lookup.landlordName())).isEqualTo(!intended);
            mismatches += intended ? 1 : 0;
        }
        assertWithin(mismatches, SAMPLE_SIZE, LandlordNameGenerator.INTENTIONAL_MISMATCH_PERCENT);
    }

    @Test
    @DisplayName("갑구는 보존 등기로 시작하고 현재 소유자는 소유권 등기 중 마지막이다")
    void ownershipStartsWithPreservationAndEndsWithCurrentOwner() {
        for (RegistryLookup lookup : sample()) {
            List<OwnershipEntry> changes = client.fetch(lookup).ownerships().stream()
                    .filter(isOwnershipChange())
                    .toList();

            assertThat(changes.get(0).rightType()).isEqualTo(OwnershipRightType.OWNERSHIP_PRESERVATION);
            assertThat(changes.get(0).cause()).isNull();
            assertThat(changes.subList(1, changes.size()))
                    .allSatisfy(e -> assertThat(e.rightType()).isEqualTo(OwnershipRightType.OWNERSHIP_TRANSFER));
            assertThat(changes.get(changes.size() - 1).isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("갑구 · 을구 모두 순위번호가 1부터 이어지고 접수일이 거꾸로 가지 않는다")
    void ranksAreSequentialAndDatesNonDecreasing() {
        for (RegistryLookup lookup : sample()) {
            RegistryDocument document = client.fetch(lookup);

            assertThat(document.ownerships()).extracting(OwnershipEntry::rankNo)
                    .containsExactlyElementsOf(sequence(document.ownerships().size()));
            assertThat(document.ownerships()).extracting(OwnershipEntry::receivedDate)
                    .isSortedAccordingTo(LocalDate::compareTo);
            assertThat(document.mortgages()).extracting(MortgageEntry::rankNo)
                    .containsExactlyElementsOf(sequence(document.mortgages().size()));
            assertThat(document.mortgages()).extracting(MortgageEntry::receivedDate)
                    .isSortedAccordingTo(LocalDate::compareTo);
        }
    }

    @Test
    @DisplayName("근저당 금액은 시세 비례 · 백만 원 단위이고 채권최고액은 원금의 120%, 선순위 임차보증금은 시세의 20%")
    void amountsFollowMarketPrice() {
        long expectedPriorTenantDeposit = MARKET_PRICE * 20 / PERCENT / 1_000_000L * 1_000_000L;
        for (RegistryLookup lookup : sample()) {
            for (MortgageEntry entry : client.fetch(lookup).mortgages()) {
                if (entry.rightType() == MortgageRightType.MORTGAGE) {
                    assertThat(entry.loanAmount() % 1_000_000L).isZero();
                    assertThat(entry.loanAmount()).isBetween(MARKET_PRICE * 20 / PERCENT - 1_000_000L,
                            MARKET_PRICE * 50 / PERCENT);
                    assertThat(entry.maxClaimAmount()).isEqualTo(entry.loanAmount() * 120 / PERCENT);
                    assertThat(entry.priorTenantDeposit()).isZero();
                } else {
                    assertThat(entry.maxClaimAmount()).isZero();
                    assertThat(entry.priorTenantDeposit()).isEqualTo(expectedPriorTenantDeposit);
                    assertThat(entry.isActive()).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("표제부 용도는 매물 유형을 따르고 출처는 MOCK 이다")
    void headerFollowsPropertyType() {
        RegistryDocument apartment = client.fetch(lookup(7L, PropertyType.APARTMENT));
        RegistryDocument officetel = client.fetch(lookup(7L, PropertyType.OFFICETEL));

        assertThat(apartment.buildingPurpose()).isEqualTo("공동주택");
        assertThat(officetel.buildingPurpose()).isEqualTo("업무시설");
        assertThat(apartment.buildingStructure()).isNotBlank();
        assertThat(apartment.dataSource()).isEqualTo(RegistryDataSource.MOCK);
    }

    @Test
    @DisplayName("표제부 주소 · 전용면적은 매물 값 그대로이고, 어긋나는 매물은 번지 · 면적만 다르며 비율이 설계값 근처다")
    void headerAddressAndAreaFollowPropertyExceptDesignedMismatches() {
        long addressMismatches = 0;
        long areaMismatches = 0;
        for (RegistryLookup lookup : sample()) {
            RegistryDocument document = client.fetch(lookup);
            String address = lookup.naturalKey().address();
            BigDecimal area = lookup.naturalKey().areaSqm();

            if (!document.registryAddress().equals(address)) {
                addressMismatches++;
                // 시험 주소는 「… 시험로 <ID>」 — 마지막 숫자(번지)만 1 커진다.
                assertThat(document.registryAddress())
                        .isEqualTo("서울특별시 시험구 시험로 " + (lookup.propertyId() + 1));
            }
            if (document.exclusiveArea().compareTo(area) != 0) {
                areaMismatches++;
                assertThat(document.exclusiveArea())
                        .isEqualByComparingTo(area.add(MockRegistryClient.AREA_MISMATCH_DELTA));
            }
        }
        assertWithin(addressMismatches, SAMPLE_SIZE, MockRegistryClient.ADDRESS_MISMATCH_PERCENT);
        assertWithin(areaMismatches, SAMPLE_SIZE, MockRegistryClient.AREA_MISMATCH_PERCENT);
    }

    @Test
    @DisplayName("근저당 건수 · 말소 · 선순위 임차인 비율이 설계값 근처다")
    void mortgageDistribution() {
        List<RegistryDocument> documents = sample().stream().map(client::fetch).toList();
        long none = documents.stream().filter(d -> mortgageCount(d) == 0).count();
        long one = documents.stream().filter(d -> mortgageCount(d) == 1).count();
        long priorTenant = documents.stream()
                .filter(d -> d.mortgages().stream().anyMatch(m -> m.rightType() == MortgageRightType.TENANCY))
                .count();
        List<MortgageEntry> mortgages = documents.stream()
                .flatMap(d -> d.mortgages().stream())
                .filter(m -> m.rightType() == MortgageRightType.MORTGAGE)
                .toList();
        long discharged = mortgages.stream().filter(m -> !m.isActive()).count();

        assertWithin(none, SAMPLE_SIZE, MockRegistryClient.NO_MORTGAGE_PERCENT);
        assertWithin(one, SAMPLE_SIZE, MockRegistryClient.ONE_MORTGAGE_PERCENT);
        assertThat(documents.stream().filter(d -> mortgageCount(d) == 2).count()).isEqualTo(SAMPLE_SIZE - none - one);
        assertWithin(priorTenant, SAMPLE_SIZE, MockRegistryClient.PRIOR_TENANT_PERCENT);
        assertWithin(discharged, mortgages.size(), MockRegistryClient.DISCHARGED_MORTGAGE_PERCENT);
    }

    @Test
    @DisplayName("권리 침해 · 경고 항목이 각각 설계 비율 근처로 섞여 나온다")
    void rightEntryDistribution() {
        List<RegistryDocument> documents = sample().stream().map(client::fetch).toList();

        assertWithin(countWith(documents, OwnershipRightType.SEIZURE), SAMPLE_SIZE,
                MockRegistryClient.SEIZURE_PERCENT);
        assertWithin(countWith(documents, OwnershipRightType.PROVISIONAL_SEIZURE), SAMPLE_SIZE,
                MockRegistryClient.PROVISIONAL_SEIZURE_PERCENT);
        assertWithin(countWith(documents, OwnershipRightType.AUCTION_COMMENCEMENT), SAMPLE_SIZE,
                MockRegistryClient.AUCTION_COMMENCEMENT_PERCENT);
        assertWithin(countWith(documents, OwnershipRightType.TRUST), SAMPLE_SIZE,
                MockRegistryClient.TRUST_PERCENT);
        assertWithin(countWith(documents, OwnershipRightType.PROVISIONAL_REGISTRATION), SAMPLE_SIZE,
                MockRegistryClient.PROVISIONAL_REGISTRATION_PERCENT);
        assertWithin(countWith(documents, OwnershipRightType.TENANCY_REGISTRATION_ORDER), SAMPLE_SIZE,
                MockRegistryClient.TENANCY_REGISTRATION_ORDER_PERCENT);
    }

    @Test
    @DisplayName("소유권이전 건수 비율이 설계값 근처다")
    void transferCountDistribution() {
        List<RegistryDocument> documents = sample().stream().map(client::fetch).toList();
        long noTransfer = documents.stream().filter(d -> transferCount(d) == 0).count();
        long oneTransfer = documents.stream().filter(d -> transferCount(d) == 1).count();

        assertWithin(noTransfer, SAMPLE_SIZE, MockRegistryClient.NO_TRANSFER_PERCENT);
        assertWithin(oneTransfer, SAMPLE_SIZE, MockRegistryClient.ONE_TRANSFER_PERCENT);
        assertThat(documents.stream().filter(d -> transferCount(d) == 2).count())
                .isEqualTo(SAMPLE_SIZE - noTransfer - oneTransfer);
    }

    // ---------- 도움 ----------

    private static List<RegistryLookup> sample() {
        return LongStream.rangeClosed(1, SAMPLE_SIZE)
                .mapToObj(id -> lookup(id, id % 2 == 0 ? PropertyType.APARTMENT : PropertyType.OFFICETEL))
                .toList();
    }

    private static RegistryLookup lookup(long propertyId, PropertyType type) {
        PropertyNaturalKey key = new PropertyNaturalKey("서울특별시 시험구 시험로 " + propertyId,
                new BigDecimal("59.90"), 3, 250_000_000L, 0L);
        return new RegistryLookup(propertyId, key, LandlordNameGenerator.generate(key), type, MARKET_PRICE);
    }

    private static Predicate<OwnershipEntry> isOwnershipChange() {
        return e -> e.rightType() == OwnershipRightType.OWNERSHIP_PRESERVATION
                || e.rightType() == OwnershipRightType.OWNERSHIP_TRANSFER;
    }

    private static List<Integer> sequence(int size) {
        return IntStream.rangeClosed(1, size).boxed().toList();
    }

    private static long mortgageCount(RegistryDocument document) {
        return document.mortgages().stream().filter(m -> m.rightType() == MortgageRightType.MORTGAGE).count();
    }

    private static long transferCount(RegistryDocument document) {
        return document.ownerships().stream()
                .filter(e -> e.rightType() == OwnershipRightType.OWNERSHIP_TRANSFER)
                .count();
    }

    private static long countWith(List<RegistryDocument> documents, OwnershipRightType type) {
        return documents.stream()
                .filter(d -> d.ownerships().stream().anyMatch(e -> e.rightType() == type))
                .count();
    }

    /** 건수가 기대값 ± 표준편차 × {@value #TOLERANCE_SIGMAS} 안인가. */
    private static void assertWithin(long actual, long trials, int percent) {
        double p = percent / (double) PERCENT;
        double expected = trials * p;
        double tolerance = TOLERANCE_SIGMAS * Math.sqrt(trials * p * (1 - p));
        assertThat((double) actual)
                .as("표본 %d건 중 기대 %.0f건(%d%%)", trials, expected, percent)
                .isBetween(expected - tolerance, expected + tolerance);
    }
}
