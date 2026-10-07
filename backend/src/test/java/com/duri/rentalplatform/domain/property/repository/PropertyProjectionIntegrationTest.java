package com.duri.rentalplatform.domain.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.vo.LoadedPriceRow;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자치구 단위 투영 조회({@link PropertyRepository#findNaturalKeysByDistrict} ·
 * {@link PropertyRepository#findLoadedPricesByDistrict})가 엔티티 경로({@code Property#naturalKey} · 저장된 시세 · 대장 키 없음
 * 판정)와 같은 값을 내는지 실제 PostgreSQL 에서 본다(#383).
 *
 * <p>매물은 JDBC 로 넣고 커밋한 뒤 읽는다. 자치구 이름은 실행마다 유일하게 잡아 다른 테스트의 행과 섞이지 않는다.
 * {@code area_sqm} 은 NOT NULL 이라 면적 null 은 저장할 수 없다 — null 처리는 층(nullable)으로 본다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PropertyProjectionIntegrationTest {

    @Autowired
    PropertyRepository propertyRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<Long> propertyIds = new ArrayList<>();
    private String district;
    private String otherDistrict;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        district = "투영시험구" + suffix;
        otherDistrict = "투영다른구" + suffix;
    }

    @AfterEach
    void cleanUp() {
        for (Long propertyId : propertyIds) {
            jdbc.update("DELETE FROM property WHERE property_id = ?", propertyId);
        }
    }

    @Test
    @DisplayName("자연키 투영은 엔티티 경로(Property::naturalKey)와 같은 집합이다")
    void naturalKeyProjectionMatchesEntityPath() {
        insert(district, "주소A", "59.90", 3, 100L, 0L, "11680");
        insert(district, "주소B", "84.9", 7, 200L, 50L, null);
        insert(district, "주소C", "33.00", null, 300L, 0L, "11680");

        List<PropertyNaturalKey> projected = propertyRepository.findNaturalKeysByDistrict(district);

        assertThat(projected).hasSize(3);
        assertThat(projected).containsExactlyInAnyOrderElementsOf(entityNaturalKeys(district));
    }

    @Test
    @DisplayName("시세 투영은 엔티티 경로가 만들던 자연키 · 시세 · 대장 키 없음 값과 같다")
    void loadedPriceProjectionMatchesEntityPath() {
        insert(district, "주소A", "59.90", 3, 100L, 0L, "11680");
        insert(district, "주소B", "84.9", 7, 200L, 50L, null);

        List<LoadedPriceRow> projected = propertyRepository.findLoadedPricesByDistrict(district);

        List<LoadedPriceRow> expected = new TransactionTemplate(transactionManager).execute(status ->
                propertyRepository.findAll().stream()
                        .filter(property -> district.equals(property.getDistrict()))
                        .map(PropertyProjectionIntegrationTest::rowOf)
                        .toList());
        assertThat(projected).hasSize(2);
        // 투영 행의 값(자연키 · 식별자 · 시세 · 근거 · 기준일 · 대장 키 없음)이 엔티티에서 읽은 값과 한 행씩 같다.
        assertThat(projected).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("면적 소수 자릿수는 정규화된다 — 84.9 는 84.90, 소수 셋째 자리 입력은 둘째 자리로 맞춘다")
    void areaScaleIsNormalizedLikeEntityPath() {
        insert(district, "주소B", "84.9", 7, 200L, 50L, null);
        insert(district, "주소D", "84.904", 7, 201L, 50L, null);

        List<PropertyNaturalKey> projected = propertyRepository.findNaturalKeysByDistrict(district);

        assertThat(projected).extracting(PropertyNaturalKey::areaSqm)
                .containsExactlyInAnyOrder(new BigDecimal("84.90"), new BigDecimal("84.90"));
        assertThat(projected).containsExactlyInAnyOrderElementsOf(entityNaturalKeys(district));
        assertThat(propertyRepository.findLoadedPricesByDistrict(district))
                .allSatisfy(row -> assertThat(row.naturalKey().areaSqm()).isEqualTo(new BigDecimal("84.90")));
    }

    @Test
    @DisplayName("대장 키 없음은 시군구 코드가 비었을 때만 참이다")
    void ledgerKeyMissingOnlyWhenSigunguCodeIsNull() {
        long keyed = insert(district, "주소A", "59.90", 3, 100L, 0L, "11680");
        long keyless = insert(district, "주소B", "84.90", 7, 200L, 50L, null);

        List<LoadedPriceRow> rows = propertyRepository.findLoadedPricesByDistrict(district);

        assertThat(rows).filteredOn(row -> row.propertyId() == keyed).singleElement()
                .extracting(LoadedPriceRow::ledgerKeyMissing).isEqualTo(false);
        assertThat(rows).filteredOn(row -> row.propertyId() == keyless).singleElement()
                .extracting(LoadedPriceRow::ledgerKeyMissing).isEqualTo(true);
    }

    @Test
    @DisplayName("다른 자치구의 매물은 두 투영 모두에서 제외된다")
    void otherDistrictIsExcluded() {
        insert(district, "주소A", "59.90", 3, 100L, 0L, "11680");
        long other = insert(otherDistrict, "주소Z", "59.90", 3, 100L, 0L, "11680");

        assertThat(propertyRepository.findNaturalKeysByDistrict(district)).hasSize(1)
                .noneMatch(key -> "주소Z".equals(key.address()));
        assertThat(propertyRepository.findLoadedPricesByDistrict(district)).hasSize(1)
                .noneMatch(row -> row.propertyId() == other);
    }

    @Test
    @DisplayName("층이 null 인 매물도 엔티티 경로와 같은 자연키(층 null)로 읽힌다")
    void nullFloorMatchesEntityPath() {
        insert(district, "주소C", "33.00", null, 300L, 0L, "11680");

        List<PropertyNaturalKey> projected = propertyRepository.findNaturalKeysByDistrict(district);

        assertThat(projected).singleElement().satisfies(key -> assertThat(key.floor()).isNull());
        assertThat(projected).containsExactlyElementsOf(entityNaturalKeys(district));
        assertThat(propertyRepository.findLoadedPricesByDistrict(district)).singleElement()
                .satisfies(row -> assertThat(row.floor()).isNull());
    }

    private List<PropertyNaturalKey> entityNaturalKeys(String targetDistrict) {
        return new TransactionTemplate(transactionManager).execute(status ->
                propertyRepository.findAll().stream()
                        .filter(property -> targetDistrict.equals(property.getDistrict()))
                        .map(Property::naturalKey)
                        .toList());
    }

    /** 지금까지 PropertyLoadWriter 가 엔티티로 만들던 값과 같은 식. */
    private static LoadedPriceRow rowOf(Property property) {
        return new LoadedPriceRow(property.getAddress(), property.getAreaSqm(), property.getFloor(),
                property.getDeposit(), property.getMonthlyRent(), property.getPropertyId(),
                property.getMarketPrice(), property.getPriceType(), property.getPriceDate(),
                property.ledgerKey() == null);
    }

    private long insert(String targetDistrict, String address, String area, Integer floor, long deposit,
            long monthlyRent, String sigunguCode) {
        boolean keyed = sigunguCode != null;
        long propertyId = jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, sigungu_code, bjdong_code, bun, ji)
                VALUES (?, ?, '김임대', ?, ?, ?, ?, ?, 300000000, 'ACTUAL_TRANSACTION',
                    DATE '2026-06-30', ?, ?, 37.5, 126.8, ?, ?, ?, ?)
                RETURNING property_id
                """, Long.class,
                address, targetDistrict, codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"),
                codeId("PROPERTY_TYPE", "APARTMENT"), codeId("PROPERTY_STATUS", "AVAILABLE"),
                deposit, monthlyRent, new BigDecimal(area), floor, sigunguCode,
                keyed ? "10100" : null, keyed ? "0100" : null, keyed ? "0001" : null);
        propertyIds.add(propertyId);
        return propertyId;
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }
}
