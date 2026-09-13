package com.duri.rentalplatform.domain.property.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertySortKey;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.DistrictCountRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PropertyMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. 다른 데이터와 섞이지 않게 실존하지 않는 자치구명을
 * 쓰고, 조회는 그 자치구로 좁힌다. 계약 · 매물 유형 코드는 FK 대상이라 V2 시드의 코드값을 코드 식별자로
 * 찾아 쓴다(값을 새로 넣으면 유일 제약에 걸린다).
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class PropertyMapperTest {

    private static final String D1 = "테스트1구";
    private static final String D2 = "테스트2구";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 20, 14, 3, 0);

    @Autowired
    PropertyMapper propertyMapper;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- 자치구 집계 ----------

    @Test
    @DisplayName("자치구 집계: 자치구별 건수와 등급 분포가 픽스처와 맞고, 미분석은 count 에만 든다")
    void districtCountsGroupByDistrictAndGrade() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100_000_000L, 0L, 37.50, 126.80, T0);
        long b = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200_000_000L, 0L, 37.50, 126.80, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 300_000_000L, 0L, 37.50, 126.80, T0);
        long d = insertProperty(D2, "DEPOSIT_ONLY", "OFFICETEL", 100_000_000L, 0L, 37.50, 126.80, T0);
        insertRisk(a, "SAFE", "60.00", true, false);
        insertRisk(b, "DANGER", "95.00", true, false);
        insertRisk(d, "CAUTION", "80.00", true, false);

        List<DistrictCountRow> rows = propertyMapper.selectDistrictCounts(
                PropertySearchCondition.ofFilter(filter(null)));

        assertThat(rows).filteredOn(r -> r.name().startsWith("테스트"))
                .extracting(DistrictCountRow::name, DistrictCountRow::totalCount, DistrictCountRow::safeCount,
                        DistrictCountRow::cautionCount, DistrictCountRow::dangerCount)
                .containsExactly(
                        tuple(D1, 3L, 1L, 0L, 1L),
                        tuple(D2, 1L, 0L, 1L, 0L));
    }

    @Test
    @DisplayName("자치구 집계: 등급 필터를 주면 분석된 매물만 센다")
    void districtCountsWithRiskGradeExcludeUnanalyzed() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100_000_000L, 0L, 37.50, 126.80, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100_000_000L, 0L, 37.50, 126.80, T0);
        insertRisk(a, "SAFE", "60.00", true, false);

        DistrictCountRequest f = new DistrictCountRequest(D1, null, null, null, null, null,
                List.of(RiskGrade.SAFE, RiskGrade.CAUTION), null, null);
        List<DistrictCountRow> rows = propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(f));

        assertThat(rows).extracting(DistrictCountRow::name, DistrictCountRow::totalCount)
                .containsExactly(tuple(D1, 1L));
    }

    @Test
    @DisplayName("자치구 집계: 최신이 아닌 분석은 등급 분포에 들지 않는다")
    void districtCountsIgnoreNonLatestAnalysis() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100_000_000L, 0L, 37.50, 126.80, T0);
        insertRisk(a, "DANGER", "95.00", false, false);
        insertRisk(a, "SAFE", "60.00", true, false);

        List<DistrictCountRow> rows = propertyMapper.selectDistrictCounts(
                PropertySearchCondition.ofFilter(filter(D1)));

        assertThat(rows).extracting(DistrictCountRow::totalCount, DistrictCountRow::safeCount,
                        DistrictCountRow::dangerCount)
                .containsExactly(tuple(1L, 1L, 0L));
    }

    @Test
    @DisplayName("자치구 집계: 보증금 · 월세 · 면적 · 계약 유형 · 매물 유형 필터를 모두 적용한다")
    void districtCountsApplyAllFilters() {
        insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 50_000_000L, 500_000L, 37.50, 126.80, T0); // 대상
        insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 50_000_000L, 900_000L, 37.50, 126.80, T0); // 월세 초과
        insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 50_000_000L, 500_000L, 37.50, 126.80, T0); // 유형
        insertProperty(D1, "DEPOSIT_ONLY", "OFFICETEL", 50_000_000L, 0L, 37.50, 126.80, T0);       // 계약
        insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 500_000_000L, 500_000L, 37.50, 126.80, T0); // 보증금

        DistrictCountRequest f = new DistrictCountRequest(D1, ContractType.MONTHLY_RENT, 10_000_000L,
                100_000_000L, 600_000L, PropertyType.OFFICETEL, null, new BigDecimal("30"), new BigDecimal("50"));
        List<DistrictCountRow> rows = propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(f));

        assertThat(rows).extracting(DistrictCountRow::totalCount).containsExactly(1L);
    }

    // ---------- 마커 ----------

    @Test
    @DisplayName("마커: 바운딩 박스 경계 위 좌표는 포함되고 바깥 좌표는 빠진다")
    void markersBoundingBoxIsInclusive() {
        long inside = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        long onEdge = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.58, 126.88, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5800001, 126.85, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.7999999, T0);

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                filter(D1), new BoundingBox(37.53, 37.58, 126.80, 126.88)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId).containsExactly(inside, onEdge);
    }

    @Test
    @DisplayName("마커: 모든 필드가 매핑되고 선순위채권은 활성 · 선순위 이력이 있을 때만 true")
    void markersMapAllFields() {
        long analyzed = insertProperty(D1, "SEMI_DEPOSIT", "APARTMENT", 230_000_000L, 100_000L, 37.55, 126.85, T0);
        long noSenior = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, null, 37.55, 126.85, T0);
        long unanalyzed = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        insertRisk(analyzed, "SAFE", "68.00", true, true);
        insertRisk(noSenior, "CAUTION", "85.00", true, false);

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                filter(D1), new BoundingBox(37.0, 38.0, 126.0, 127.0)));

        assertThat(markers).hasSize(3);
        PropertyMarkerResponse first = markers.get(0);
        assertThat(first.propertyId()).isEqualTo(analyzed);
        assertThat(first.latitude()).isEqualByComparingTo("37.55");
        assertThat(first.longitude()).isEqualByComparingTo("126.85");
        assertThat(first.deposit()).isEqualTo(230_000_000L);
        assertThat(first.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(first.contractType()).isEqualTo(ContractType.SEMI_DEPOSIT);
        assertThat(first.monthlyRent()).isEqualTo(100_000L);
        assertThat(first.district()).isEqualTo(D1);
        assertThat(first.debtRatio()).isEqualByComparingTo("68.00");
        assertThat(first.hasSeniorDebt()).isTrue();

        assertThat(markers.get(1).hasSeniorDebt()).isFalse();
        assertThat(markers.get(1).monthlyRent()).isZero();

        PropertyMarkerResponse none = markers.get(2);
        assertThat(none.propertyId()).isEqualTo(unanalyzed);
        assertThat(none.riskGrade()).isNull();
        assertThat(none.debtRatio()).isNull();
        assertThat(none.hasSeniorDebt()).isNull();
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록: 모든 필드가 매핑된다")
    void listMapsAllFields() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "OFFICETEL", 230_000_000L, 0L, 37.55, 126.85, T0);
        insertRisk(id, "SAFE", "68.00", true, false);

        List<PropertyListResponse> rows = propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, false,
                null, null, null, null, 10));

        assertThat(rows).hasSize(1);
        PropertyListResponse row = rows.get(0);
        assertThat(row.propertyId()).isEqualTo(id);
        assertThat(row.district()).isEqualTo(D1);
        assertThat(row.address()).isEqualTo("서울특별시 " + D1 + " 시험로 1");
        assertThat(row.propertyType()).isEqualTo(PropertyType.OFFICETEL);
        assertThat(row.contractType()).isEqualTo(ContractType.DEPOSIT_ONLY);
        assertThat(row.deposit()).isEqualTo(230_000_000L);
        assertThat(row.monthlyRent()).isZero();
        assertThat(row.areaSqm()).isEqualByComparingTo("42.50");
        assertThat(row.floor()).isEqualTo(3);
        assertThat(row.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(row.debtRatio()).isEqualByComparingTo("68.00");
        // 저장값은 서울 벽시계 시각이다. 같은 시각을 +09:00 으로 돌려준다.
        assertThat(row.registeredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(row.registeredAt().toLocalDateTime()).isEqualTo(T0);
    }

    @Test
    @DisplayName("목록: 등록일 내림차순 키셋 — 같은 등록일은 식별자로 갈라 다음 페이지로 밀리거나 겹치지 않는다")
    void listRegisteredAtDescKeysetWithTies() {
        long p1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long p2 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long p3 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long newest = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(1));

        List<Long> first = ids(propertyMapper.selectList(
                list(PropertySortKey.REGISTERED_AT, false, null, null, null, null, 2)));
        List<Long> middle = ids(propertyMapper.selectList(
                list(PropertySortKey.REGISTERED_AT, false, null, null, T0, p3, 2)));
        List<Long> last = ids(propertyMapper.selectList(
                list(PropertySortKey.REGISTERED_AT, false, null, null, T0, p2, 2)));
        List<Long> empty = ids(propertyMapper.selectList(
                list(PropertySortKey.REGISTERED_AT, false, null, null, T0, p1, 2)));

        assertThat(first).containsExactly(newest, p3);
        assertThat(middle).containsExactly(p2, p1);
        assertThat(last).containsExactly(p1);
        assertThat(empty).isEmpty();
    }

    @Test
    @DisplayName("목록: 보증금 오름차순 키셋 — 같은 보증금은 식별자 오름차순")
    void listDepositAscKeyset() {
        long cheapA = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long cheapB = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long pricey = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);

        List<Long> all = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, true,
                null, null, null, null, 10)));
        List<Long> afterA = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, true,
                100L, null, null, cheapA, 10)));

        assertThat(all).containsExactly(cheapA, cheapB, pricey);
        assertThat(afterA).containsExactly(cheapB, pricey);
    }

    @Test
    @DisplayName("목록: 전세가율 정렬에서 미분석 매물은 오름차순 · 내림차순 모두 맨 뒤다")
    void listDebtRatioPutsUnanalyzedLast() {
        long low = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long high = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long none = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        insertRisk(low, "SAFE", "50.00", true, false);
        insertRisk(high, "DANGER", "95.00", true, false);

        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEBT_RATIO, true,
                null, null, null, null, 10)))).containsExactly(low, high, none);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEBT_RATIO, false,
                null, null, null, null, 10)))).containsExactly(high, low, none);
        // 내림차순에서 low 다음 페이지 — 대체값(-1000)으로 키셋이 이어져 미분석이 나온다.
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEBT_RATIO, false,
                null, new BigDecimal("50.00"), null, low, 10)))).containsExactly(none);
    }

    // ---------- 상세 ----------

    @Test
    @DisplayName("상세: 모든 필드가 매핑되고 관심 등록 여부는 해당 사용자에게만 true")
    void detailMapsAllFieldsAndWishlist() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 230_000_000L, 0L, 37.5501234, 126.8497561, T0);
        insertRisk(id, "SAFE", "68.00", true, false);
        long owner = insertUser();
        long other = insertUser();
        jdbc.update("INSERT INTO wishlist (user_id, property_id, alert_condition) VALUES (?, ?, 'GRADE_CHANGE')",
                owner, id);

        PropertyDetailRow row = propertyMapper.selectDetail(new PropertyDetailCondition(id, owner));

        assertThat(row.propertyId()).isEqualTo(id);
        assertThat(row.district()).isEqualTo(D1);
        assertThat(row.address()).isEqualTo("서울특별시 " + D1 + " 시험로 1");
        assertThat(row.latitude()).isEqualByComparingTo("37.5501234");
        assertThat(row.longitude()).isEqualByComparingTo("126.8497561");
        assertThat(row.propertyType()).isEqualTo(PropertyType.APARTMENT);
        assertThat(row.contractType()).isEqualTo(ContractType.DEPOSIT_ONLY);
        assertThat(row.deposit()).isEqualTo(230_000_000L);
        assertThat(row.monthlyRent()).isZero();
        assertThat(row.areaSqm()).isEqualByComparingTo("42.50");
        assertThat(row.floor()).isEqualTo(3);
        assertThat(row.landlordName()).isEqualTo("김임대");
        assertThat(row.marketPrice()).isEqualTo(340_000_000L);
        assertThat(row.priceType()).isEqualTo(PriceType.ACTUAL_TRANSACTION);
        assertThat(row.priceDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(row.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(row.debtRatio()).isEqualByComparingTo("68.00");
        assertThat(row.insuranceEligible()).isTrue();
        assertThat(row.wishlisted()).isTrue();
        // 행은 드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답의 정적 팩토리가 한다. 시각만 확인한다.
        assertThat(row.registeredAt().toInstant()).isEqualTo(T0.toInstant(ZoneOffset.ofHours(9)));

        assertThat(propertyMapper.selectDetail(new PropertyDetailCondition(id, other)).wishlisted()).isFalse();
        assertThat(propertyMapper.selectDetail(new PropertyDetailCondition(id, null)).wishlisted()).isFalse();
    }

    @Test
    @DisplayName("상세: 미분석 매물은 위험 필드가 null 이다")
    void detailUnanalyzed() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);

        PropertyDetailRow row = propertyMapper.selectDetail(new PropertyDetailCondition(id, null));

        assertThat(row.riskGrade()).isNull();
        assertThat(row.debtRatio()).isNull();
        assertThat(row.insuranceEligible()).isNull();
    }

    @Test
    @DisplayName("상세: 없는 매물은 null")
    void detailNotFound() {
        assertThat(propertyMapper.selectDetail(new PropertyDetailCondition(Long.MAX_VALUE, null))).isNull();
    }

    // ---------- 픽스처 ----------

    private static DistrictCountRequest filter(String district) {
        return new DistrictCountRequest(district, null, null, null, null, null, null, null, null);
    }

    private static PropertySearchCondition list(PropertySortKey sortKey, boolean ascending, Long lastDeposit,
            BigDecimal lastDebtRatio, LocalDateTime lastRegisteredAt, Long lastId, int limit) {
        BigDecimal nullDebtRatio = new BigDecimal(ascending ? "1000" : "-1000");
        return PropertySearchCondition.ofList(filter(D1), sortKey, ascending, nullDebtRatio,
                lastDeposit, lastDebtRatio, lastRegisteredAt, lastId, limit);
    }

    private static List<Long> ids(List<PropertyListResponse> rows) {
        return rows.stream().map(PropertyListResponse::propertyId).toList();
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject(
                "SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private long insertProperty(String district, String contractType, String propertyType, long deposit,
            Long monthlyRent, double lat, double lng, LocalDateTime registeredAt) {
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, registered_at)
                VALUES (?, ?, '김임대', ?, ?, ?, ?, ?, 340000000, 'ACTUAL_TRANSACTION', DATE '2026-06-30',
                    42.50, 3, ?, ?, ?)
                RETURNING property_id
                """, Long.class,
                "서울특별시 " + district + " 시험로 1", district,
                codeId("CONTRACT_TYPE", contractType), codeId("PROPERTY_TYPE", propertyType),
                codeId("PROPERTY_STATUS", "AVAILABLE"), deposit, monthlyRent,
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), registeredAt);
    }

    /** 최신 분석 한 건. FK 가 요구하는 표제부 · 건축물대장 최소 행을 함께 넣는다. */
    private void insertRisk(long propertyId, String grade, String leaseRatio, boolean latest, boolean seniorDebt) {
        Long registryId = jdbc.queryForObject(
                "INSERT INTO building_registry (property_id, building_purpose) VALUES (?, '공동주택') RETURNING registry_id",
                Long.class, propertyId);
        Long ledgerId = jdbc.queryForObject("""
                INSERT INTO building_ledger (property_id, ledger_address, owner_name, building_purpose, building_area)
                VALUES (?, '주소', '김임대', '공동주택', 42.50) RETURNING ledger_id
                """, Long.class, propertyId);
        jdbc.update("""
                INSERT INTO risk_analysis (property_id, registry_id, ledger_id, lease_ratio, risk_grade,
                    is_latest, insurance_eligible_yn)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                """, propertyId, registryId, ledgerId, new BigDecimal(leaseRatio), grade, latest);
        // 비활성 선순위 이력은 늘 넣는다 — is_active 조건이 빠지면 seniorDebt=false 인 매물도 true 가 된다.
        jdbc.update("""
                INSERT INTO mortgage_history (registry_id, priority_no, right_type, senior_debt_yn, is_active)
                VALUES (?, 1, 'MORTGAGE', TRUE, FALSE)
                """, registryId);
        if (seniorDebt) {
            jdbc.update("""
                    INSERT INTO mortgage_history (registry_id, priority_no, right_type, senior_debt_yn, is_active)
                    VALUES (?, 2, 'MORTGAGE', TRUE, TRUE)
                    """, registryId);
        }
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('시험', 800) RETURNING user_id", Long.class);
    }
}
