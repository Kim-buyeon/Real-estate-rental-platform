package com.duri.rentalplatform.domain.property.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyDetailCondition;
import com.duri.rentalplatform.domain.property.dto.condition.ReanalysisPendingPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertyIdsCondition;
import com.duri.rentalplatform.domain.property.dto.condition.PropertySearchCondition;
import com.duri.rentalplatform.domain.property.dto.condition.UnanalyzedPropertyCondition;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyListResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertySortKey;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.BoundingBox;
import com.duri.rentalplatform.domain.property.vo.DistrictCountRow;
import com.duri.rentalplatform.domain.property.vo.LoadedPriceRow;
import com.duri.rentalplatform.domain.property.vo.MapClusterCellRow;
import com.duri.rentalplatform.domain.property.vo.MarkerCandidateRow;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
@Tag("integration")
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

    @PersistenceContext
    EntityManager entityManager;

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
    @DisplayName("마커: 소수 7자리 경계 좌표는 네 변 모두 포함되고 한 자리 벗어난 좌표는 빠진다")
    void markersBoundingBoxKeepsSevenDecimalEdges() {
        double minLat = 37.5000001;
        double maxLat = 37.6000009;
        double minLng = 126.8000001;
        double maxLng = 126.9000009;
        long onMinLat = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, minLat, 126.85, T0);
        long onMaxLat = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, maxLat, 126.85, T0);
        long onMinLng = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, minLng, T0);
        long onMaxLng = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, maxLng, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5000000, 126.85, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.6000010, 126.85, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.8000000, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.9000010, T0);

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                filter(D1), new BoundingBox(minLat, maxLat, minLng, maxLng)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId)
                .containsExactlyInAnyOrder(onMinLat, onMaxLat, onMinLng, onMaxLng);
    }

    @Test
    @DisplayName("마커: 활성 근저당이면 선순위 여부 표지와 무관하게 선순위채권이 true, 말소뿐이면 false")
    void markersSeniorDebtDependsOnlyOnActiveMortgage() {
        long activeNonSenior = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        long onlyCancelled = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.56, 126.85, T0);
        insertRisk(activeNonSenior, "CAUTION", "85.00", true, false);
        insertRisk(onlyCancelled, "CAUTION", "85.00", true, false);
        long registryId = jdbc.queryForObject(
                "SELECT registry_id FROM building_registry WHERE property_id = ?", Long.class, activeNonSenior);
        jdbc.update("""
                INSERT INTO mortgage_history (registry_id, priority_no, right_type, senior_debt_yn, is_active)
                VALUES (?, 3, 'MORTGAGE', FALSE, TRUE)
                """, registryId);

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                filter(D1), new BoundingBox(37.0, 38.0, 126.0, 127.0)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId, PropertyMarkerResponse::hasSeniorDebt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(activeNonSenior, true),
                        org.assertj.core.groups.Tuple.tuple(onlyCancelled, false));
    }

    @Test
    @DisplayName("마커: 모든 필드가 매핑되고 선순위채권은 활성 이력이 있을 때만 true")
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

    // ---------- 반경 후보(명세 1.3 반경 상한) ----------

    @Test
    @DisplayName("반경 후보: 바운딩 박스 경계 위 좌표는 포함되고 바깥 좌표는 빠지며, 식별자 · 좌표가 매핑된다")
    void markerCandidatesBoundingBoxIsInclusiveAndMapsFields() {
        long inside = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5501234, 126.8497561, T0);
        long onEdge = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.58, 126.88, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5800001, 126.85, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.7999999, T0);

        List<MarkerCandidateRow> rows = propertyMapper.selectMarkerCandidates(PropertySearchCondition.ofMarkers(
                filter(D1), new BoundingBox(37.53, 37.58, 126.80, 126.88)));

        assertThat(rows).extracting(MarkerCandidateRow::propertyId).containsExactlyInAnyOrder(inside, onEdge);
        MarkerCandidateRow first = rows.stream().filter(r -> r.propertyId() == inside).findFirst().orElseThrow();
        assertThat(first.latitude()).isEqualByComparingTo("37.5501234");
        assertThat(first.longitude()).isEqualByComparingTo("126.8497561");
    }

    @Test
    @DisplayName("반경 후보: 계약 유형 · 등급 필터가 적용되고 미분석은 등급 필터에 걸리지 않는다")
    void markerCandidatesApplyContractTypeAndGradeFilter() {
        long safeRent = insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 1L, 500_000L, 37.55, 126.85, T0);
        long dangerRent = insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 1L, 500_000L, 37.55, 126.85, T0);
        long safeDeposit = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 1L, 500_000L, 37.55, 126.85, T0); // 미분석
        insertRisk(safeRent, "SAFE", "60.00", true, false);
        insertRisk(dangerRent, "DANGER", "95.00", true, false);
        insertRisk(safeDeposit, "SAFE", "60.00", true, false);

        DistrictCountRequest f = new DistrictCountRequest(D1, ContractType.MONTHLY_RENT, null, null, null, null,
                List.of(RiskGrade.SAFE), null, null);
        List<MarkerCandidateRow> rows = propertyMapper.selectMarkerCandidates(
                PropertySearchCondition.ofMarkers(f, new BoundingBox(37.0, 38.0, 126.0, 127.0)));

        assertThat(rows).extracting(MarkerCandidateRow::propertyId).containsExactly(safeRent);
    }

    // ---------- 최신 판정 비정규화 열(V22) ----------

    @Test
    @DisplayName("비정규화: 지도 · 목록 · 집계 · 상세의 등급 · 전세가율은 판정 표가 아니라 매물 열에서 온다")
    void riskColumnsComeFromProperty() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        insertRisk(id, "SAFE", "60.00", true, false);
        // 판정 표는 SAFE · 60.00 그대로 두고 매물 열만 다르게 — 매퍼가 매물 열을 읽으면 DANGER · 91.50 이 나온다.
        setPropertyRisk(id, "DANGER", "91.50");
        BoundingBox box = new BoundingBox(37.0, 38.0, 126.0, 128.0);
        DistrictCountRequest dangerOnly = new DistrictCountRequest(D1, null, null, null, null, null,
                List.of(RiskGrade.DANGER), null, null);

        assertThat(propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(filter(D1))))
                .extracting(DistrictCountRow::safeCount, DistrictCountRow::dangerCount)
                .containsExactly(tuple(0L, 1L));
        assertThat(propertyMapper.selectClusterCells(clusters(filter(D1), GRID_BOX)))
                .extracting(MapClusterCellRow::safeCount, MapClusterCellRow::dangerCount)
                .containsExactly(tuple(0L, 1L));
        assertThat(propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(filter(D1), box)))
                .extracting(PropertyMarkerResponse::riskGrade, PropertyMarkerResponse::debtRatio)
                .containsExactly(tuple(RiskGrade.DANGER, new BigDecimal("91.50")));
        assertThat(propertyMapper.selectMarkersByIds(new PropertyIdsCondition(List.of(id))))
                .extracting(PropertyMarkerResponse::riskGrade).containsExactly(RiskGrade.DANGER);
        assertThat(propertyMapper.selectMarkerCandidates(PropertySearchCondition.ofMarkers(dangerOnly, box)))
                .extracting(MarkerCandidateRow::propertyId).containsExactly(id);
        assertThat(propertyMapper.selectList(listBy(dangerOnly, PropertySortKey.DEBT_RATIO, true, null, null, 10)))
                .extracting(PropertyListResponse::riskGrade, PropertyListResponse::debtRatio)
                .containsExactly(tuple(RiskGrade.DANGER, new BigDecimal("91.50")));
        PropertyDetailRow detail = propertyMapper.selectDetail(new PropertyDetailCondition(id, null));
        assertThat(detail.riskGrade()).isEqualTo(RiskGrade.DANGER);
        assertThat(detail.debtRatio()).isEqualByComparingTo("91.50");
        // 보증 가입 여부는 판정 표에만 있어 최신 판정 행에서 읽는다.
        assertThat(detail.insuranceEligible()).isTrue();
    }

    @Test
    @DisplayName("비정규화: 자치구 없는 전세가율 인덱스 목록도 매물 열의 전세가율 · 등급으로 정렬 · 거른다")
    void leaseRatioIndexListReadsPropertyColumns() {
        long[] fixture = insertLeaseRatioFixture();
        // 판정 표 순서와 반대가 되게 매물 열만 바꾼다 — low 를 가장 높게.
        setPropertyRisk(fixture[3], "DANGER", "99.00");

        List<Long> rows = mineOf(propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, false, null, null, 1000)), fixture);

        assertThat(rows.get(0)).isEqualTo(fixture[3]);
    }

    // ---------- 지도 묶음 ----------

    /**
     * 지도 묶음 시험 영역. 칸 크기가 0.0625(2 의 거듭제곱 분수)라 칸 경계 좌표가 double 로 정확히 표현된다 —
     * 경계 위 좌표가 어느 칸에 드는지를 부동소수 오차 없이 본다.
     */
    private static final BoundingBox GRID_BOX = new BoundingBox(37.0, 37.75, 127.0, 127.75);
    private static final double CELL = 0.0625;

    private static PropertySearchCondition clusters(DistrictCountRequest f, BoundingBox box) {
        return clusters(f, box, 12, 12);
    }

    /** 서비스와 같은 계산 — 칸 높이 = 높이 ÷ 행, 칸 너비 = 폭 ÷ 열, 마지막 번호는 축별(명세 1.12). */
    private static PropertySearchCondition clusters(DistrictCountRequest f, BoundingBox box, int rows, int cols) {
        return PropertySearchCondition.ofClusters(f, box, (box.maxLat() - box.minLat()) / rows,
                (box.maxLng() - box.minLng()) / cols, rows - 1, cols - 1);
    }

    @Test
    @DisplayName("지도 묶음: 칸 경계 위 좌표는 위쪽 칸에, 바로 아래 좌표는 아래쪽 칸에 든다")
    void clusterCellBoundaryBelongsToUpperCell() {
        long below = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.0 + CELL - 0.0000001, 127.0, T0);
        long onEdge = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.0 + CELL, 127.0 + CELL, T0);

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(clusters(filter(D1), GRID_BOX));

        assertThat(cells).extracting(MapClusterCellRow::rowIndex, MapClusterCellRow::colIndex,
                        MapClusterCellRow::count, MapClusterCellRow::representativeId)
                .containsExactly(tuple(0, 0, 1L, below), tuple(1, 1, 1L, onEdge));
    }

    @Test
    @DisplayName("지도 묶음: 최대 경계 위 좌표는 12번이 아니라 11번 칸에, 최소 경계는 0번 칸에 든다")
    void clusterMaxEdgeIsClampedToLastCell() {
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.0, 127.0, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.75, 127.75, T0);

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(clusters(filter(D1), GRID_BOX));

        assertThat(cells).extracting(MapClusterCellRow::rowIndex, MapClusterCellRow::colIndex)
                .containsExactly(tuple(0, 0), tuple(11, 11));
    }

    @Test
    @DisplayName("지도 묶음: 칸별 건수 · 등급별 건수 · 평균 좌표 · 대표 식별자가 픽스처와 맞고 미분석은 UNANALYZED 로 센다")
    void clusterAggregatesGradesAndAverage() {
        long safe1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        long safe2 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.31, 127.31, T0);
        long caution = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        long danger = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0); // 미분석
        insertRisk(safe1, "SAFE", "60.00", true, false);
        insertRisk(safe2, "SAFE", "60.00", true, false);
        insertRisk(caution, "DANGER", "95.00", false, false); // 최신이 아닌 분석은 세지 않는다
        insertRisk(caution, "CAUTION", "80.00", true, false);
        insertRisk(danger, "DANGER", "95.00", true, false);

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(clusters(filter(D1), GRID_BOX));

        assertThat(cells).hasSize(1);
        MapClusterCellRow cell = cells.get(0);
        // (37.30 - 37.0) / 0.0625 = 4.8, (37.31 - 37.0) / 0.0625 = 4.96 → 둘 다 4
        assertThat(cell.rowIndex()).isEqualTo(4);
        assertThat(cell.colIndex()).isEqualTo(4);
        assertThat(cell.count()).isEqualTo(5L);
        assertThat(cell.safeCount()).isEqualTo(2L);
        assertThat(cell.cautionCount()).isEqualTo(1L);
        assertThat(cell.dangerCount()).isEqualTo(1L);
        assertThat(cell.unanalyzedCount()).isEqualTo(1L);
        assertThat(cell.latitude()).isEqualByComparingTo("37.302");
        assertThat(cell.longitude()).isEqualByComparingTo("127.302");
        assertThat(cell.representativeId()).isEqualTo(safe1);
    }

    @Test
    @DisplayName("지도 묶음: 공통 필터와 표시 영역 밖 매물을 제외하고 센다")
    void clusterAppliesFilterAndBox() {
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);       // 대상
        insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 1L, 500_000L, 37.30, 127.30, T0); // 계약 유형
        insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);       // 자치구
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.7500001, 127.30, T0);  // 영역 밖
        long graded = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        insertRisk(graded, "SAFE", "60.00", true, false);

        DistrictCountRequest f = new DistrictCountRequest(D1, ContractType.DEPOSIT_ONLY, null, null, null, null,
                null, null, null);
        assertThat(propertyMapper.selectClusterCells(clusters(f, GRID_BOX)))
                .extracting(MapClusterCellRow::count).containsExactly(2L);

        DistrictCountRequest safeOnly = new DistrictCountRequest(D1, null, null, null, null, null,
                List.of(RiskGrade.SAFE), null, null);
        assertThat(propertyMapper.selectClusterCells(clusters(safeOnly, GRID_BOX)))
                .extracting(MapClusterCellRow::count, MapClusterCellRow::safeCount,
                        MapClusterCellRow::unanalyzedCount)
                .containsExactly(tuple(1L, 1L, 0L));
    }

    @Test
    @DisplayName("지도 묶음: 2행 × 3열 격자에서 행은 위도 칸 높이 · 0 ~ 1, 열은 경도 칸 너비 · 0 ~ 2 로 따로 자른다")
    void clusterRowsAndColsAreClampedPerAxis() {
        // 높이 0.5 ÷ 2행 = 0.25, 폭 0.75 ÷ 3열 = 0.25 — 칸 경계가 double 로 정확하다.
        BoundingBox box = new BoundingBox(37.0, 37.5, 127.0, 127.75);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.0, 127.0, T0);   // 최소 경계 → (0, 0)
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.1, 127.6, T0);   // 0.4 · 2.4 → (0, 2)
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.49, 127.1, T0);  // 1.96 · 0.4 → (1, 0)
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.25, 127.5, T0);  // 경계 위 1 · 2 → (1, 2)
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 127.75, T0);  // 최대 경계 2 · 3 → 잘려 (1, 2)

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(clusters(filter(D1), box, 2, 3));

        // 행 · 열의 상한이 바뀌면(행에 열 상한 2) 최대 경계가 (2, 2) 로, (열에 행 상한 1) (0, 2) · (1, 2) 가 열 1 로 간다.
        assertThat(cells).extracting(MapClusterCellRow::rowIndex, MapClusterCellRow::colIndex, MapClusterCellRow::count)
                .containsExactly(tuple(0, 0, 1L), tuple(0, 2, 1L), tuple(1, 0, 1L), tuple(1, 2, 2L));
    }

    @Test
    @DisplayName("지도 묶음: 표시 영역의 폭이 0 인 축은 나누지 않고 모두 0번 칸이다")
    void clusterZeroSpanAxisUsesFirstCell() {
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.10, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.70, T0);

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(
                clusters(filter(D1), new BoundingBox(37.30, 37.30, 127.0, 127.75)));

        assertThat(cells).extracting(MapClusterCellRow::rowIndex, MapClusterCellRow::colIndex)
                .containsExactly(tuple(0, 1), tuple(0, 11));
    }

    @Test
    @DisplayName("식별자 마커: 목록의 매물만 식별자 순으로, 마커 필드를 채워 돌려준다")
    void markersByIdsReturnOnlyListed() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 230_000_000L, 0L, 37.55, 126.85, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        long c = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.55, 126.85, T0);
        insertRisk(a, "SAFE", "68.00", true, true);

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkersByIds(
                new PropertyIdsCondition(List.of(c, a)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId).containsExactly(a, c);
        PropertyMarkerResponse first = markers.get(0);
        assertThat(first.deposit()).isEqualTo(230_000_000L);
        assertThat(first.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(first.contractType()).isEqualTo(ContractType.DEPOSIT_ONLY);
        assertThat(first.district()).isEqualTo(D1);
        assertThat(first.debtRatio()).isEqualByComparingTo("68.00");
        assertThat(first.hasSeniorDebt()).isTrue();
        assertThat(first.latitude()).isEqualByComparingTo("37.55");
        assertThat(first.longitude()).isEqualByComparingTo("126.85");
        assertThat(first.monthlyRent()).isZero();
        assertThat(markers.get(1).riskGrade()).isNull();
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

    /**
     * 등록일순 키셋(행 비교, #437) 픽스처. 식별자 발급 순서가 등록 시각과 섞이게 넣어, 행 비교 방향이 뒤집히거나 식별자만으로
     * 가르면 결과가 달라지게 한다. 반환: [lateEarly(T0+2일), earlyEarly(T0), m1, m2, m3(모두 T0+1일), lateLate(T0+2일),
     * earlyLate(T0)].
     */
    private long[] insertRegisteredKeysetFixture(String district) {
        long lateEarly = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(2));
        long earlyEarly = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long m1 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(1));
        long m2 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(1));
        long m3 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(1));
        long lateLate = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0.plusDays(2));
        long earlyLate = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        return new long[] {lateEarly, earlyEarly, m1, m2, m3, lateLate, earlyLate};
    }

    @Test
    @DisplayName("목록: 등록일 오름차순 키셋 — 쪽 경계에 걸친 같은 등록 시각은 식별자 오름차순으로 이어지고 빠지거나 겹치지 않는다")
    void listRegisteredAtAscKeysetWithTiesAtBoundary() {
        long[] fx = insertRegisteredKeysetFixture(D1);
        long lateEarly = fx[0];
        long earlyEarly = fx[1];
        long m1 = fx[2];
        long m2 = fx[3];
        long m3 = fx[4];
        long lateLate = fx[5];
        long earlyLate = fx[6];

        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, null, null, 2)))).containsExactly(earlyEarly, earlyLate);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, T0, earlyLate, 2)))).containsExactly(m1, m2);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, T0.plusDays(1), m2, 2)))).containsExactly(m3, lateEarly);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, T0.plusDays(2), lateEarly, 2)))).containsExactly(lateLate);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, T0.plusDays(2), lateLate, 2)))).isEmpty();
    }

    @Test
    @DisplayName("목록: 등록일 키셋은 행 비교다 — 커서보다 이른/늦은 등록은 식별자와 무관하게 방향대로 가르고, 같은 등록 시각만 식별자로 가른다")
    void listRegisteredAtKeysetSeparatesByTimeThenId() {
        long[] fx = insertRegisteredKeysetFixture(D1);
        long lateEarly = fx[0];
        long earlyEarly = fx[1];
        long m1 = fx[2];
        long m2 = fx[3];
        long m3 = fx[4];
        long lateLate = fx[5];
        long earlyLate = fx[6];

        // 오름: 커서 (T0+1일, m2) 뒤 — 더 늦은 등록은 식별자가 작아도(lateEarly) 들고, 더 이른 등록은 식별자가 커도(earlyLate) 빠진다.
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, T0.plusDays(1), m2, 100)))).containsExactly(m3, lateEarly, lateLate);
        // 내림: 반대 — 더 이른 등록은 식별자가 커도(earlyLate) 들고, 더 늦은 등록은 식별자가 작아도(lateEarly) 빠진다.
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, false,
                null, null, T0.plusDays(1), m2, 100)))).containsExactly(m1, earlyLate, earlyEarly);
        // 전체 순서 — 등록 시각, 같으면 식별자(내림은 둘 다 내림).
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, true,
                null, null, null, null, 100)))).containsExactly(earlyEarly, earlyLate, m1, m2, m3, lateEarly, lateLate);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.REGISTERED_AT, false,
                null, null, null, null, 100)))).containsExactly(lateLate, lateEarly, m3, m2, m1, earlyLate, earlyEarly);
    }

    @Test
    @DisplayName("목록: 자치구 없는 등록일순 + 커서 — 여러 자치구 매물이 하나의 등록 시각 · 식별자 순서로 이어진다")
    void listRegisteredAtKeysetWithoutDistrictSpansDistricts() {
        // 다른 데이터와 섞이지 않게 먼 미래 시각을 쓴다.
        LocalDateTime base = LocalDateTime.of(2099, 1, 1, 0, 0, 0);
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, base.plusDays(1));
        long b = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, base.plusDays(1));
        long c = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, base.plusDays(1));
        long earlyD2 = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, base);
        long lateD1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, base.plusDays(2));
        long[] fx = {a, b, c, earlyD2, lateD1};

        assertThat(mineOf(propertyMapper.selectList(registeredListBy(filter(null), true, base.plusDays(1), b, 1000)), fx))
                .containsExactly(c, lateD1);
        assertThat(mineOf(propertyMapper.selectList(registeredListBy(filter(null), false, base.plusDays(1), b, 1000)), fx))
                .containsExactly(a, earlyD2);
        assertThat(mineOf(propertyMapper.selectList(registeredListBy(filter(null), false, null, null, 1000)), fx))
                .containsExactly(lateD1, c, b, a, earlyD2);
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

    /**
     * 보증금순 키셋(행 비교, V26) 픽스처. 식별자 발급 순서가 보증금과 섞이게 넣어, 행 비교 방향이 뒤집히거나 식별자만으로
     * 가르면 결과가 달라지게 한다. 반환: [highEarly(300), lowEarly(100), e1, e2, e3(모두 200), highLate(300), lowLate(100)].
     */
    private long[] insertDepositKeysetFixture(String district) {
        long highEarly = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 300L, 0L, 37.5, 126.8, T0);
        long lowEarly = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long e1 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long e2 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long e3 = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long highLate = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 300L, 0L, 37.5, 126.8, T0);
        long lowLate = insertProperty(district, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        return new long[] {highEarly, lowEarly, e1, e2, e3, highLate, lowLate};
    }

    @Test
    @DisplayName("목록: 보증금 내림차순 키셋 — 경계에 걸친 같은 보증금은 식별자 내림차순으로 이어지고 빠지거나 겹치지 않는다")
    void listDepositDescKeysetWithTiesAtBoundary() {
        long cheap = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long e1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long e2 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long e3 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long top = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 300L, 0L, 37.5, 126.8, T0);

        List<Long> first = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                null, null, null, null, 2)));
        List<Long> second = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                200L, null, null, e3, 2)));
        List<Long> third = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                200L, null, null, e1, 2)));
        List<Long> empty = ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                100L, null, null, cheap, 2)));

        assertThat(first).containsExactly(top, e3);
        assertThat(second).containsExactly(e2, e1);
        assertThat(third).containsExactly(cheap);
        assertThat(empty).isEmpty();
    }

    @Test
    @DisplayName("목록: 보증금 키셋은 행 비교다 — 커서보다 작은/큰 보증금은 식별자와 무관하게 방향대로 가르고, 같은 보증금만 식별자로 가른다")
    void listDepositKeysetSeparatesByDepositThenId() {
        long[] fx = insertDepositKeysetFixture(D1);
        long highEarly = fx[0];
        long lowEarly = fx[1];
        long e1 = fx[2];
        long e2 = fx[3];
        long e3 = fx[4];
        long highLate = fx[5];
        long lowLate = fx[6];

        // 오름: 커서 (200, e2) 뒤 — 더 큰 보증금은 식별자가 작아도(highEarly) 들고, 더 작은 보증금은 식별자가 커도(lowLate) 빠진다.
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, true,
                200L, null, null, e2, 100)))).containsExactly(e3, highEarly, highLate);
        // 내림: 반대 — 더 작은 보증금은 식별자가 커도(lowLate) 들고, 더 큰 보증금은 식별자가 작아도(highEarly) 빠진다.
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                200L, null, null, e2, 100)))).containsExactly(e1, lowLate, lowEarly);
        // 전체 순서 — 보증금, 같으면 식별자(내림은 둘 다 내림).
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, true,
                null, null, null, null, 100)))).containsExactly(lowEarly, lowLate, e1, e2, e3, highEarly, highLate);
        assertThat(ids(propertyMapper.selectList(list(PropertySortKey.DEPOSIT, false,
                null, null, null, null, 100)))).containsExactly(highLate, highEarly, e3, e2, e1, lowLate, lowEarly);
    }

    @Test
    @DisplayName("목록: 자치구 없는 보증금순 + 커서 — 여러 자치구 매물이 하나의 보증금 · 식별자 순서로 이어진다")
    void listDepositKeysetWithoutDistrictSpansDistricts() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long b = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long c = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        long cheapD2 = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long priceyD1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 300L, 0L, 37.5, 126.8, T0);
        long[] fx = {a, b, c, cheapD2, priceyD1};

        // 자치구 없으면 다른 데이터가 섞일 수 있어 이 테스트의 매물만 걸러 본다.
        assertThat(mineOf(propertyMapper.selectList(depositListBy(filter(null), true, 200L, b, 1000)), fx))
                .containsExactly(c, priceyD1);
        assertThat(mineOf(propertyMapper.selectList(depositListBy(filter(null), false, 200L, b, 1000)), fx))
                .containsExactly(a, cheapD2);
        assertThat(mineOf(propertyMapper.selectList(depositListBy(filter(null), false, null, null, 1000)), fx))
                .containsExactly(priceyD1, c, b, a, cheapD2);
    }

    @Test
    @DisplayName("목록: 보증금 키셋은 계약 유형 필터와 함께 걸어도 필터 안의 매물만 커서 뒤로 이어 준다")
    void listDepositKeysetWithContractTypeFilter() {
        long[] m = insertTypeMatrix(); // 보증금 100(전세) 200(월세) 300(전세) 400(월세), 식별자도 같은 방향

        assertThat(ids(propertyMapper.selectList(depositListBy(typeFilter(ContractType.MONTHLY_RENT, null),
                false, 400L, m[3], 10)))).containsExactly(m[1]);
        assertThat(ids(propertyMapper.selectList(depositListBy(typeFilter(ContractType.MONTHLY_RENT, null),
                true, 200L, m[1], 10)))).containsExactly(m[3]);
        assertThat(ids(propertyMapper.selectList(depositListBy(typeFilter(ContractType.DEPOSIT_ONLY, null),
                false, 300L, m[2], 10)))).containsExactly(m[0]);
    }

    @Test
    @DisplayName("목록: 보증금 키셋은 보증금 범위 필터와 함께 걸어도 범위 밖을 끌어오지 않는다")
    void listDepositKeysetWithDepositRangeFilter() {
        long[] m = insertTypeMatrix(); // 보증금 100 200 300 400
        DistrictCountRequest range = new DistrictCountRequest(D1, null, 150L, 350L, null, null, null, null, null);

        assertThat(ids(propertyMapper.selectList(depositListBy(range, false, 300L, m[2], 10))))
                .containsExactly(m[1]); // 100 은 범위 밖
        assertThat(ids(propertyMapper.selectList(depositListBy(range, true, 200L, m[1], 10))))
                .containsExactly(m[2]); // 400 은 범위 밖
    }

    @Autowired
    SqlSessionFactory sqlSessionFactory;

    /**
     * 질의 모양 확인 — 플래너의 선택이 아니다. 테스트 데이터가 작아 실제 플래너는 인덱스를 고르지 않으므로 순차 스캔 · 정렬 ·
     * 비트맵 스캔을 꺼 두고, 「자치구 없는 보증금 내림 + 커서」 질의가 ix_property_deposit 를 탐색 조건(Index Cond)으로 받고
     * 별도 Sort 없이 인덱스 순서를 쓸 수 있는가만 본다. OR 식으로 되돌리면 Index Cond 에 커서 비교가 오르지 못한다.
     */
    @Test
    @DisplayName("목록: 자치구 없는 보증금 내림 + 커서 질의는 ix_property_deposit 를 탐색 조건으로 받고 Sort 노드가 없다 (질의 모양 확인, 플래너 선택 아님)")
    void depositDescKeysetCanUseDepositIndexWithoutSort() {
        List<String> plan = explainSelectList(depositListBy(filter(null), false, 200L, 1L, 10));

        String text = String.join("\n", plan);
        assertThat(text).contains("ix_property_deposit");
        assertThat(plan).anyMatch(l -> l.contains("Index Cond") && l.contains("deposit"));
        assertThat(text).doesNotContain("Sort");
    }

    /**
     * 질의 모양 확인(위 보증금 테스트와 같은 방식, 플래너 선택 아님). 등록일순 + 커서 질의가 등록일 인덱스를 탐색 조건으로 받고
     * Sort 없이 인덱스 순서를 쓸 수 있는가 — 자치구 없으면 idx_property_registered(V19), 오름은 같은 인덱스를 뒤로 읽는다.
     * OR 식으로 되돌리면 Index Cond 에 등록일 커서 비교가 오르지 못한다.
     */
    @Test
    @DisplayName("목록: 자치구 없는 등록일순 + 커서 질의는 idx_property_registered 를 탐색 조건으로 받고 Sort 노드가 없다 — 내림 · 오름 (질의 모양 확인, 플래너 선택 아님)")
    void registeredKeysetWithoutDistrictCanUseRegisteredIndexWithoutSort() {
        for (boolean ascending : new boolean[] {false, true}) {
            List<String> plan = explainSelectList(registeredListBy(filter(null), ascending, T0, 1L, 10));

            String text = String.join("\n", plan);
            assertThat(text).as("ascending=%s", ascending).contains("idx_property_registered");
            assertThat(plan).as("ascending=%s", ascending)
                    .anyMatch(l -> l.contains("Index Cond") && l.contains("registered_at"));
            assertThat(text).as("ascending=%s", ascending).doesNotContain("Sort");
        }
    }

    @Test
    @DisplayName("목록: 자치구 있는 등록일 내림 + 커서 질의는 등록일 커서를 인덱스 탐색 조건으로 받고 Sort 노드가 없다 (질의 모양 확인, 플래너 선택 아님)")
    void registeredKeysetWithDistrictUsesCursorAsIndexCond() {
        List<String> plan = explainSelectList(registeredListBy(filter(D1), false, T0, 1L, 10));

        assertThat(plan).anyMatch(l -> l.contains("Index Cond") && l.contains("registered_at"));
        assertThat(String.join("\n", plan)).doesNotContain("Sort");
    }

    /** selectList 의 실제 SQL 을 순차 스캔 · 정렬 · 비트맵 스캔을 끈 채 EXPLAIN 한 줄들. 질의 모양 확인 전용. */
    private List<String> explainSelectList(PropertySearchCondition cond) {
        org.apache.ibatis.session.Configuration cfg = sqlSessionFactory.getConfiguration();
        BoundSql bound = cfg.getMappedStatement(
                "com.duri.rentalplatform.domain.property.mapper.PropertyMapper.selectList").getBoundSql(cond);
        org.apache.ibatis.reflection.MetaObject meta = cfg.newMetaObject(cond);

        return jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL enable_sort = off");
                st.execute("SET LOCAL enable_bitmapscan = off");
            }
            try (java.sql.PreparedStatement ps = con.prepareStatement("EXPLAIN " + bound.getSql())) {
                int i = 1;
                for (org.apache.ibatis.mapping.ParameterMapping pm : bound.getParameterMappings()) {
                    ps.setObject(i++, meta.getValue(pm.getProperty()));
                }
                List<String> lines = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return lines;
            }
        });
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

    // ---------- 목록: 자치구 없는 전세가율 정렬과 자치구 있는 전세가율 정렬 ----------

    @Test
    @DisplayName("목록: 자치구 없이 전세가율 정렬하면 미분석은 맨 뒤, 같은 전세가율은 식별자 순이다")
    void listDebtRatioWithoutDistrictOrdersLikeWithDistrict() {
        long tieA = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long tieB = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long high = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long none = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        insertRisk(tieA, "SAFE", "50.00", true, false);
        insertRisk(tieB, "SAFE", "50.00", true, false);
        insertRisk(high, "DANGER", "95.00", true, false);

        // 자치구가 없으면 다른 데이터가 섞이므로 이 테스트의 매물만 걸러 순서를 본다.
        List<Long> mine = List.of(tieA, tieB, high, none);
        List<Long> asc = ids(propertyMapper.selectList(listBy(filter(null), PropertySortKey.DEBT_RATIO, true,
                null, null, 1000))).stream().filter(mine::contains).toList();
        List<Long> desc = ids(propertyMapper.selectList(listBy(filter(null), PropertySortKey.DEBT_RATIO, false,
                null, null, 1000))).stream().filter(mine::contains).toList();

        assertThat(asc).containsExactly(tieA, tieB, high, none);
        assertThat(desc).containsExactly(high, tieB, tieA, none);
    }

    // ---------- 목록: 전세가율 인덱스 출발(selectListByLeaseRatioIndex, V20) ----------

    /** 앞부분 대조 픽스처 — 같은 전세가율 동률 셋 · 등급 다른 둘 · 판정 없음 하나. */
    private long[] insertLeaseRatioFixture() {
        long t1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long t2 = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long t3 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long low = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long high = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long none = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        insertRisk(t1, "CAUTION", "70.00", true, false);
        insertRisk(t2, "CAUTION", "70.00", true, false);
        insertRisk(t3, "CAUTION", "70.00", true, false);
        insertRisk(low, "SAFE", "30.00", true, false);
        insertRisk(high, "DANGER", "95.00", true, false);
        insertRisk(low, "DANGER", "99.00", false, false); // 옛 분석은 순서에 끼지 않는다
        return new long[] {t1, t2, t3, low, high, none};
    }

    private List<Long> mineOf(List<PropertyListResponse> rows, long[] fixture) {
        List<Long> mine = java.util.Arrays.stream(fixture).boxed().toList();
        return ids(rows).stream().filter(mine::contains).toList();
    }

    /** selectList 결과에서 판정 없는 매물(끝부분)을 뺀 것 — 새 쿼리가 돌려줘야 할 앞부분. */
    private List<Long> headOfSelectList(DistrictCountRequest f, boolean asc, BigDecimal lastRatio, Long lastId,
            long[] fixture) {
        long none = fixture[5];
        return mineOf(propertyMapper.selectList(listBy(f, PropertySortKey.DEBT_RATIO, asc, lastRatio, lastId, 1000)),
                fixture).stream().filter(id -> id != none).toList();
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: 오름 · 내림 결과와 순서가 selectList 의 앞부분과 같고 판정 없는 매물은 빠진다")
    void leaseRatioIndexMatchesSelectListHead() {
        long[] fx = insertLeaseRatioFixture();
        for (boolean asc : new boolean[] {true, false}) {
            List<Long> viaIndex = mineOf(propertyMapper.selectListByLeaseRatioIndex(
                    listBy(filter(null), PropertySortKey.DEBT_RATIO, asc, null, null, 1000)), fx);

            assertThat(viaIndex).isEqualTo(headOfSelectList(filter(null), asc, null, null, fx));
            assertThat(viaIndex).doesNotContain(fx[5]).hasSize(5);
        }
        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, true, null, null, 1000)), fx))
                .containsExactly(fx[3], fx[0], fx[1], fx[2], fx[4]);
        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, false, null, null, 1000)), fx))
                .containsExactly(fx[4], fx[2], fx[1], fx[0], fx[3]);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: 등급 필터는 selectList 와 같은 매물을 같은 순서로 거른다")
    void leaseRatioIndexAppliesRiskGradeFilterLikeSelectList() {
        long[] fx = insertLeaseRatioFixture();
        DistrictCountRequest f = new DistrictCountRequest(null, null, null, null, null, null,
                List.of(RiskGrade.CAUTION, RiskGrade.DANGER), null, null);

        for (boolean asc : new boolean[] {true, false}) {
            List<Long> viaIndex = mineOf(propertyMapper.selectListByLeaseRatioIndex(
                    listBy(f, PropertySortKey.DEBT_RATIO, asc, null, null, 1000)), fx);

            assertThat(viaIndex).isEqualTo(headOfSelectList(f, asc, null, null, fx));
            assertThat(viaIndex).doesNotContain(fx[3]).hasSize(4); // SAFE 는 빠진다
        }
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: 같은 전세가율 동률 한가운데서 이은 커서가 selectList 와 같고 앞 페이지와 겹치지 않는다")
    void leaseRatioIndexKeysetInsideTiesMatchesSelectList() {
        long[] fx = insertLeaseRatioFixture();
        BigDecimal seventy = new BigDecimal("70.00");

        for (boolean asc : new boolean[] {true, false}) {
            List<Long> viaIndex = mineOf(propertyMapper.selectListByLeaseRatioIndex(
                    listBy(filter(null), PropertySortKey.DEBT_RATIO, asc, seventy, fx[1], 1000)), fx);

            assertThat(viaIndex).isEqualTo(headOfSelectList(filter(null), asc, seventy, fx[1], fx));
            assertThat(viaIndex).doesNotContain(fx[1]); // 커서 행 자신은 다음 페이지에 없다
        }
        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, true, seventy, fx[1], 1000)), fx))
                .containsExactly(fx[2], fx[4]);
        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, false, seventy, fx[1], 1000)), fx))
                .containsExactly(fx[0], fx[3]);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: limit 은 정렬 앞에서부터 자른다")
    void leaseRatioIndexLimitCutsFromTheFront() {
        long[] fx = insertLeaseRatioFixture();

        List<PropertyListResponse> rows = propertyMapper.selectListByLeaseRatioIndex(
                listBy(filter(null), PropertySortKey.DEBT_RATIO, true, null, null, 2));

        assertThat(ids(rows)).containsExactly(fx[3], fx[0]);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: SELECT 목록의 모든 필드를 값으로 채우고 selectList 의 같은 행과 같다")
    void leaseRatioIndexMapsAllFields() {
        long id = insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 230_000_000L, 350_000L, 37.55, 126.85, T0);
        insertRisk(id, "SAFE", "68.00", true, false);

        List<PropertyListResponse> rows = propertyMapper.selectListByLeaseRatioIndex(listBy(filter(D1),
                PropertySortKey.DEBT_RATIO, true, null, null, 10));

        assertThat(rows).hasSize(1);
        PropertyListResponse row = rows.get(0);
        assertThat(row.propertyId()).isEqualTo(id);
        assertThat(row.district()).isEqualTo(D1);
        assertThat(row.address()).isEqualTo("서울특별시 " + D1 + " 시험로 1");
        assertThat(row.propertyType()).isEqualTo(PropertyType.OFFICETEL);
        assertThat(row.contractType()).isEqualTo(ContractType.MONTHLY_RENT);
        assertThat(row.deposit()).isEqualTo(230_000_000L);
        assertThat(row.monthlyRent()).isEqualTo(350_000L);
        assertThat(row.areaSqm()).isEqualByComparingTo("42.50");
        assertThat(row.floor()).isEqualTo(3);
        assertThat(row.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(row.debtRatio()).isEqualByComparingTo("68.00");
        assertThat(row.registeredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(row.registeredAt().toLocalDateTime()).isEqualTo(T0);

        PropertyListResponse viaSelectList = propertyMapper.selectList(list(PropertySortKey.DEBT_RATIO, true,
                null, null, null, null, 10)).get(0);
        assertThat(row).isEqualTo(viaSelectList);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발: 앞부분 마지막 행 다음 커서로 부르면 빈 결과다")
    void leaseRatioIndexAfterLastHeadRowIsEmpty() {
        long[] fx = insertLeaseRatioFixture();

        // 오름차순 앞부분의 마지막은 high(95.00), 내림차순은 low(30.00)
        assertThat(propertyMapper.selectListByLeaseRatioIndex(listBy(filter(null), PropertySortKey.DEBT_RATIO,
                true, new BigDecimal("95.00"), fx[4], 1000))).isEmpty();
        assertThat(propertyMapper.selectListByLeaseRatioIndex(listBy(filter(null), PropertySortKey.DEBT_RATIO,
                false, new BigDecimal("30.00"), fx[3], 1000))).isEmpty();
    }

    // ---------- 목록: 전세가율 인덱스 출발 + 매물 조건 필터(selectListByLeaseRatioIndex, V28 · #453) ----------

    /**
     * 필터 대조 픽스처 — 자치구 없이 읽으므로 식별자 순서(0 → 4)와 전세가율 순서(4 → 3 → 1 = 2 → 0)가 다르다. 반환은
     * [0 ~ 4: 판정 있음, 5: 판정 없음]. 월세 NULL 이 둘(0 · 4)이고 1 · 2 는 전세가율이 같다.
     *
     * <pre>
     * 번호  계약  유형      보증금  월세     면적    위도   경도    등급     전세가율
     * 0    전세  아파트    100    NULL    30.00  37.50 126.80  SAFE     80.00
     * 1    월세  아파트    200    300000  60.00  37.55 126.85  CAUTION  70.00
     * 2    전세  오피스텔  300    0       85.00  37.60 126.90  CAUTION  70.00
     * 3    월세  오피스텔  400    600000  120.00 37.65 126.95  DANGER   40.00
     * 4    전세  아파트    500    NULL    150.00 37.70 127.00  SAFE     20.00
     * 5    전세  아파트    250    0       60.00  37.60 126.90  (없음)
     * </pre>
     */
    private long[] insertLeaseRatioFilterFixture() {
        return new long[] {
            insertFilterRow("DEPOSIT_ONLY", "APARTMENT", 100L, null, "30.00", 37.50, 126.80, "SAFE", "80.00"),
            insertFilterRow("MONTHLY_RENT", "APARTMENT", 200L, 300_000L, "60.00", 37.55, 126.85, "CAUTION", "70.00"),
            insertFilterRow("DEPOSIT_ONLY", "OFFICETEL", 300L, 0L, "85.00", 37.60, 126.90, "CAUTION", "70.00"),
            insertFilterRow("MONTHLY_RENT", "OFFICETEL", 400L, 600_000L, "120.00", 37.65, 126.95, "DANGER", "40.00"),
            insertFilterRow("DEPOSIT_ONLY", "APARTMENT", 500L, null, "150.00", 37.70, 127.00, "SAFE", "20.00"),
            insertFilterRow("DEPOSIT_ONLY", "APARTMENT", 250L, 0L, "60.00", 37.60, 126.90, null, null)
        };
    }

    private long insertFilterRow(String contractType, String propertyType, long deposit, Long monthlyRent,
            String area, double lat, double lng, String grade, String leaseRatio) {
        long id = insertProperty(D1, contractType, propertyType, deposit, monthlyRent, lat, lng, T0);
        jdbc.update("UPDATE property SET area_sqm = ? WHERE property_id = ?", new BigDecimal(area), id);
        if (grade != null) {
            insertRisk(id, grade, leaseRatio, true, false);
        }
        return id;
    }

    private static DistrictCountRequest propertyFilter(ContractType contractType, Long depositMin, Long depositMax,
            Long monthlyRentMax, PropertyType propertyType, List<RiskGrade> grades, String areaMin, String areaMax) {
        return new DistrictCountRequest(null, contractType, depositMin, depositMax, monthlyRentMax, propertyType,
                grades, areaMin == null ? null : new BigDecimal(areaMin),
                areaMax == null ? null : new BigDecimal(areaMax));
    }

    /** 자치구 없는 전세가율순 목록 조건 — 필터에 영역 박스(null 이면 없음)를 더한다. 목록 조건 팩토리는 박스를 비우므로 직접 만든다. */
    private static PropertySearchCondition ratioCondition(DistrictCountRequest f, BoundingBox box, boolean ascending,
            BigDecimal lastDebtRatio, Long lastId, int limit) {
        PropertySearchCondition c = listBy(f, PropertySortKey.DEBT_RATIO, ascending, lastDebtRatio, lastId, limit);
        if (box == null) {
            return c;
        }
        return new PropertySearchCondition(c.district(), c.contractType(), c.depositMin(), c.depositMax(),
                c.monthlyRentMax(), c.propertyType(), c.riskGrades(), c.areaMin(), c.areaMax(), box.minLat(),
                box.maxLat(), box.minLng(), box.maxLng(), c.sortKey(), c.ascending(), c.nullDebtRatio(),
                c.lastDeposit(), c.lastDebtRatio(), c.lastRegisteredAt(), c.lastId(), c.limit(), null, null, null,
                null);
    }

    private static List<Long> pick(long[] fx, int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(i -> fx[i]).toList();
    }

    /**
     * 같은 조건을 두 방향으로 인덱스 매퍼와 selectList(판정 없는 끝부분은 뺀다)에 넣어 결과 · 순서가 같고, 맞는 매물이
     * 기대한 번호들뿐인지 본다. 두 구현이 함께 틀리는 것을 막으려고 기대 번호를 따로 단언한다.
     */
    private void assertIndexMatchesSelectList(DistrictCountRequest f, BoundingBox box, long[] fx,
            int... expectedIndexes) {
        long none = fx[5];
        for (boolean asc : new boolean[] {true, false}) {
            PropertySearchCondition c = ratioCondition(f, box, asc, null, null, 1000);
            List<Long> viaIndex = mineOf(propertyMapper.selectListByLeaseRatioIndex(c), fx);
            List<Long> viaSelectList = mineOf(propertyMapper.selectList(c), fx).stream()
                    .filter(id -> id != none).toList();

            assertThat(viaIndex).as("asc=%s 인덱스 결과가 selectList 앞부분과 같다", asc).isEqualTo(viaSelectList);
            assertThat(viaIndex).as("asc=%s 맞는 매물", asc)
                    .containsExactlyInAnyOrderElementsOf(pick(fx, expectedIndexes));
        }
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 보증금 하한 · 상한 · 범위는 경계값을 포함하고 selectList 와 같다")
    void leaseRatioIndexDepositFilterMatchesSelectList() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, 300L, null, null, null, null, null, null), null, fx, 2, 3, 4);
        assertIndexMatchesSelectList(propertyFilter(null, null, 300L, null, null, null, null, null), null, fx, 0, 1, 2);
        assertIndexMatchesSelectList(propertyFilter(null, 200L, 400L, null, null, null, null, null), null, fx, 1, 2, 3);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 월세 상한은 월세 NULL 을 0 으로 보고 경계값을 포함한다")
    void leaseRatioIndexMonthlyRentFilterTreatsNullAsZero() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, null, null, 300_000L, null, null, null, null), null, fx,
                0, 1, 2, 4);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, 0L, null, null, null, null), null, fx, 0, 2, 4);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 면적 하한 · 상한 · 범위는 경계값을 포함하고 selectList 와 같다")
    void leaseRatioIndexAreaFilterMatchesSelectList() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, "85", null), null, fx, 2, 3, 4);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, null, "60"), null, fx, 0, 1);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, "60", "120"), null, fx,
                1, 2, 3);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 계약 유형 · 매물 유형은 각각 · 함께 selectList 와 같다")
    void leaseRatioIndexContractAndPropertyTypeFilterMatchesSelectList() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(ContractType.MONTHLY_RENT, null, null, null, null, null, null,
                null), null, fx, 1, 3);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, PropertyType.OFFICETEL, null, null,
                null), null, fx, 2, 3);
        assertIndexMatchesSelectList(propertyFilter(ContractType.MONTHLY_RENT, null, null, null,
                PropertyType.OFFICETEL, null, null, null), null, fx, 3);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 영역 박스는 네 변을 포함하고 selectList 와 같다")
    void leaseRatioIndexBoundingBoxFilterMatchesSelectList() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, null, null),
                new BoundingBox(37.55, 37.65, 126.85, 126.95), fx, 1, 2, 3);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 등급 하나 · 여럿이 selectList 와 같다")
    void leaseRatioIndexRiskGradeFilterMatchesSelectListForOneAndMany() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, List.of(RiskGrade.SAFE), null, null),
                null, fx, 0, 4);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, List.of(RiskGrade.CAUTION), null,
                null), null, fx, 1, 2);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null,
                List.of(RiskGrade.CAUTION, RiskGrade.DANGER), null, null), null, fx, 1, 2, 3);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 여러 조건을 한꺼번에 걸어도 모두 만족하는 매물만 selectList 와 같게 나온다")
    void leaseRatioIndexCombinedFiltersMatchSelectList() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(ContractType.DEPOSIT_ONLY, 100L, 500L, 0L, PropertyType.APARTMENT,
                List.of(RiskGrade.SAFE), "30", "150"), new BoundingBox(37.50, 37.70, 126.80, 127.00), fx, 0, 4);
        assertIndexMatchesSelectList(propertyFilter(ContractType.DEPOSIT_ONLY, null, null, null, null, null, "85",
                null), null, fx, 2, 4);
        assertIndexMatchesSelectList(propertyFilter(ContractType.MONTHLY_RENT, null, 300L, 300_000L, null, null,
                null, null), null, fx, 1);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 아무것도 맞지 않는 필터는 빈 목록이다")
    void leaseRatioIndexNothingMatchingFilterReturnsEmpty() {
        long[] fx = insertLeaseRatioFilterFixture();

        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, "1000", null), null, fx);
        assertIndexMatchesSelectList(propertyFilter(null, 500L, null, null, PropertyType.OFFICETEL, null, null, null),
                null, fx);
        assertIndexMatchesSelectList(propertyFilter(null, null, null, null, null, null, null, null),
                new BoundingBox(10.0, 11.0, 10.0, 11.0), fx);
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 필터: 정렬은 전세가율 → 식별자이고 필터가 있어도 표와 이은 뒤 순서가 유지된다")
    void leaseRatioIndexKeepsRatioOrderAfterJoinWithFilter() {
        long[] fx = insertLeaseRatioFilterFixture();
        DistrictCountRequest f = propertyFilter(null, 200L, null, null, null, null, null, null);

        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                ratioCondition(f, null, true, null, null, 1000)), fx))
                .containsExactly(fx[4], fx[3], fx[1], fx[2]);
        assertThat(mineOf(propertyMapper.selectListByLeaseRatioIndex(
                ratioCondition(f, null, false, null, null, 1000)), fx))
                .containsExactly(fx[2], fx[1], fx[3], fx[4]);
    }

    /** 커서를 마지막 행으로 옮기며 pageSize 건씩 끝까지 읽은 식별자(픽스처 밖 데이터 포함). */
    private List<Long> walkLeaseRatioIndex(DistrictCountRequest f, boolean asc, int pageSize) {
        List<Long> all = new ArrayList<>();
        BigDecimal lastRatio = null;
        Long lastId = null;
        for (int i = 0; i < 50; i++) {
            List<PropertyListResponse> page = propertyMapper.selectListByLeaseRatioIndex(
                    ratioCondition(f, null, asc, lastRatio, lastId, pageSize));
            if (page.isEmpty()) {
                break;
            }
            all.addAll(ids(page));
            PropertyListResponse last = page.get(page.size() - 1);
            lastRatio = last.debtRatio();
            lastId = last.propertyId();
        }
        return all;
    }

    @Test
    @DisplayName("전세가율 인덱스 출발 커서: 같은 전세가율이 쪽 경계에 걸려도 이어 읽으면 빠짐 · 중복 없이 selectList 순서와 같다 (필터 있음 · 없음, 두 방향)")
    void leaseRatioIndexKeysetWalkAcrossTiesHasNoGapOrDuplicate() {
        long[] fx = insertLeaseRatioFilterFixture();
        long none = fx[5];
        List<Long> mine = java.util.Arrays.stream(fx).boxed().toList();
        // 보증금 ≤ 400 은 전세가율 70.00 동률 둘(1 · 2)을 모두 남긴다.
        for (DistrictCountRequest f : List.of(propertyFilter(null, null, null, null, null, null, null, null),
                propertyFilter(null, null, 400L, null, null, null, null, null))) {
            for (boolean asc : new boolean[] {true, false}) {
                List<Long> expected = mineOf(propertyMapper.selectList(ratioCondition(f, null, asc, null, null, 1000)),
                        fx).stream().filter(id -> id != none).toList();
                for (int pageSize = 1; pageSize <= 3; pageSize++) {
                    List<Long> walked = walkLeaseRatioIndex(f, asc, pageSize).stream().filter(mine::contains).toList();

                    assertThat(walked).as("asc=%s pageSize=%s", asc, pageSize).isEqualTo(expected);
                    assertThat(walked).as("중복 없음 asc=%s pageSize=%s", asc, pageSize).doesNotHaveDuplicates();
                }
            }
        }
    }

    // ---------- 필터 조각 분리(filterConditions + filter, #453): 조건이 일부만 · 하나도 없을 때의 WHERE ----------

    @Test
    @DisplayName("필터 조각: 자치구 없이 보증금 상한 하나만 걸어도 자치구 집계가 그 조건만 적용해 센다")
    void districtCountsWithSingleNonDistrictFilterApplyOnlyThatCondition() {
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 200L, 0L, 37.5, 126.8, T0);
        Long expected = jdbc.queryForObject("SELECT count(*) FROM property WHERE deposit <= 150", Long.class);

        long counted = propertyMapper.selectDistrictCounts(PropertySearchCondition.ofFilter(
                propertyFilter(null, null, 150L, null, null, null, null, null)))
                .stream().mapToLong(DistrictCountRow::totalCount).sum();

        assertThat(counted).isEqualTo(expected);
    }

    @Test
    @DisplayName("필터 조각: 표시 영역만 있고 다른 조건이 없으면 마커 · 지도 묶음이 영역 안 매물만 전부 센다")
    void markersAndClustersWithBoxOnlyApplyJustTheBox() {
        // 서울 밖 좌표를 써서 다른 데이터와 섞이지 않게 한다.
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 33.30, 127.30, T0);
        long b = insertProperty(D2, "MONTHLY_RENT", "OFFICETEL", 9L, 500_000L, 33.40, 127.40, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 34.50, 127.30, T0); // 영역 밖
        BoundingBox box = new BoundingBox(33.0, 33.75, 127.0, 127.75);

        assertThat(propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(filter(null), box)))
                .extracting(PropertyMarkerResponse::propertyId).containsExactlyInAnyOrder(a, b);
        assertThat(propertyMapper.selectClusterCells(clusters(filter(null), box)))
                .extracting(MapClusterCellRow::count).containsExactlyInAnyOrder(1L, 1L);
    }

    @Test
    @DisplayName("목록: 끝부분 커서(대체값, 극단 식별자)로 selectList 를 부르면 판정 없는 매물만 전부 순서대로 나온다")
    void selectListTailCursorReturnsOnlyUnjudged() {
        long[] fx = insertLeaseRatioFixture();
        long none2 = insertProperty(D2, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        List<Long> unjudged = List.of(fx[5], none2);

        List<PropertyListResponse> asc = propertyMapper.selectList(listBy(filter(null), PropertySortKey.DEBT_RATIO,
                true, new BigDecimal("1000"), Long.MIN_VALUE, 1000));
        List<PropertyListResponse> desc = propertyMapper.selectList(listBy(filter(null), PropertySortKey.DEBT_RATIO,
                false, new BigDecimal("-1000"), Long.MAX_VALUE, 1000));

        assertThat(mineOf(asc, fx)).containsExactly(fx[5]);
        assertThat(ids(asc)).filteredOn(unjudged::contains).containsExactly(fx[5], none2);
        assertThat(ids(desc)).filteredOn(unjudged::contains).containsExactly(none2, fx[5]);
        // 판정 있는 매물은 하나도 섞이지 않는다
        assertThat(asc).extracting(PropertyListResponse::riskGrade).containsOnlyNulls();
        assertThat(desc).extracting(PropertyListResponse::riskGrade).containsOnlyNulls();
    }

    @Test
    @DisplayName("목록: 전세가율 오름차순 키셋 — 같은 전세가율은 식별자로 갈라 페이지 사이에서 밀리거나 겹치지 않는다")
    void listDebtRatioAscKeysetWithTies() {
        long t1 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long t2 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long t3 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long top = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        insertRisk(t1, "SAFE", "50.00", true, false);
        insertRisk(t2, "SAFE", "50.00", true, false);
        insertRisk(t3, "SAFE", "50.00", true, false);
        insertRisk(top, "DANGER", "95.00", true, false);
        BigDecimal fifty = new BigDecimal("50.00");

        List<Long> first = ids(propertyMapper.selectList(listBy(filter(D1), PropertySortKey.DEBT_RATIO, true,
                null, null, 2)));
        List<Long> second = ids(propertyMapper.selectList(listBy(filter(D1), PropertySortKey.DEBT_RATIO, true,
                fifty, t2, 2)));
        List<Long> empty = ids(propertyMapper.selectList(listBy(filter(D1), PropertySortKey.DEBT_RATIO, true,
                new BigDecimal("95.00"), top, 2)));

        assertThat(first).containsExactly(t1, t2);
        assertThat(second).containsExactly(t3, top);
        assertThat(empty).isEmpty();
    }

    @Test
    @DisplayName("목록: 자치구 · 전세가율 정렬에서 최신이 아닌 분석은 정렬에도 행 수에도 영향이 없다")
    void listDebtRatioLateralUsesOnlyLatestAnalysis() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long b = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long c = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        // 옛 분석의 전세가율은 최신과 반대 쪽으로 튀게 둔다 — 섞여 들면 순서가 뒤집힌다.
        insertRisk(a, "DANGER", "99.00", false, false);
        insertRisk(a, "SAFE", "60.00", true, false);
        insertRisk(b, "SAFE", "10.00", false, false);
        insertRisk(b, "CAUTION", "70.00", true, false);
        insertRisk(c, "SAFE", "20.00", false, false);
        insertRisk(c, "DANGER", "80.00", true, false);

        List<PropertyListResponse> asc = propertyMapper.selectList(listBy(filter(D1), PropertySortKey.DEBT_RATIO,
                true, null, null, 10));
        List<Long> desc = ids(propertyMapper.selectList(listBy(filter(D1), PropertySortKey.DEBT_RATIO, false,
                null, null, 10)));

        assertThat(asc).extracting(PropertyListResponse::propertyId, PropertyListResponse::riskGrade)
                .containsExactly(tuple(a, RiskGrade.SAFE), tuple(b, RiskGrade.CAUTION),
                        tuple(c, RiskGrade.DANGER));
        assertThat(desc).containsExactly(c, b, a);
    }

    @Test
    @DisplayName("목록: 자치구 · 전세가율 정렬에서 등급 필터는 최신 분석의 등급으로만 거르고 미분석은 뺀다")
    void listDebtRatioLateralAppliesRiskGradeFilterOnLatest() {
        long safe = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long caution = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long danger = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        long wasSafe = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.5, 126.8, T0); // 미분석
        insertRisk(safe, "SAFE", "50.00", true, false);
        insertRisk(caution, "CAUTION", "80.00", true, false);
        insertRisk(danger, "DANGER", "95.00", true, false);
        insertRisk(wasSafe, "SAFE", "40.00", false, false); // 옛 등급만 SAFE
        insertRisk(wasSafe, "DANGER", "97.00", true, false);

        DistrictCountRequest f = new DistrictCountRequest(D1, null, null, null, null, null,
                List.of(RiskGrade.SAFE, RiskGrade.CAUTION), null, null);

        assertThat(ids(propertyMapper.selectList(listBy(f, PropertySortKey.DEBT_RATIO, true, null, null, 10))))
                .containsExactly(safe, caution);
    }

    @Test
    @DisplayName("목록: 계약 유형 필터는 자치구 · 전세가율 정렬에서 적용된다")
    void listContractTypeFilterOnLateralBranch() {
        long[] ids = insertTypeMatrix();

        List<Long> rows = ids(propertyMapper.selectList(listBy(typeFilter(ContractType.MONTHLY_RENT, null),
                PropertySortKey.DEBT_RATIO, true, null, null, 10)));

        assertThat(rows).containsExactly(ids[1], ids[3]);
    }

    @Test
    @DisplayName("목록: 계약 유형 필터는 다른 정렬에서도 적용된다")
    void listContractTypeFilterOnPlainBranch() {
        long[] ids = insertTypeMatrix();

        List<Long> rows = ids(propertyMapper.selectList(listBy(typeFilter(ContractType.MONTHLY_RENT, null),
                PropertySortKey.DEPOSIT, true, null, null, 10)));

        assertThat(rows).containsExactly(ids[1], ids[3]);
    }

    @Test
    @DisplayName("목록: 매물 유형 필터는 자치구 · 전세가율 정렬에서 적용된다")
    void listPropertyTypeFilterOnLateralBranch() {
        long[] ids = insertTypeMatrix();

        List<Long> rows = ids(propertyMapper.selectList(listBy(typeFilter(null, PropertyType.OFFICETEL),
                PropertySortKey.DEBT_RATIO, true, null, null, 10)));

        assertThat(rows).containsExactly(ids[2], ids[3]);
    }

    @Test
    @DisplayName("목록: 매물 유형 필터는 다른 정렬에서도 적용된다")
    void listPropertyTypeFilterOnPlainBranch() {
        long[] ids = insertTypeMatrix();

        List<Long> rows = ids(propertyMapper.selectList(listBy(typeFilter(null, PropertyType.OFFICETEL),
                PropertySortKey.REGISTERED_AT, true, null, null, 10)));

        assertThat(rows).containsExactly(ids[2], ids[3]);
    }

    @Test
    @DisplayName("마커: 계약 유형 필터가 적용된다")
    void markersApplyContractTypeFilter() {
        long[] ids = insertTypeMatrix();

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                typeFilter(ContractType.MONTHLY_RENT, null), new BoundingBox(37.0, 38.0, 126.0, 127.0)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId).containsExactly(ids[1], ids[3]);
    }

    @Test
    @DisplayName("마커: 매물 유형 필터가 적용된다")
    void markersApplyPropertyTypeFilter() {
        long[] ids = insertTypeMatrix();

        List<PropertyMarkerResponse> markers = propertyMapper.selectMarkers(PropertySearchCondition.ofMarkers(
                typeFilter(null, PropertyType.OFFICETEL), new BoundingBox(37.0, 38.0, 126.0, 127.0)));

        assertThat(markers).extracting(PropertyMarkerResponse::propertyId).containsExactly(ids[2], ids[3]);
    }

    @Test
    @DisplayName("지도 묶음: 매물 유형 필터가 적용된다")
    void clusterAppliesPropertyTypeFilter() {
        insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.30, 127.30, T0);
        insertProperty(D1, "DEPOSIT_ONLY", "OFFICETEL", 1L, 0L, 37.30, 127.30, T0);
        insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 1L, 500_000L, 37.30, 127.30, T0);

        List<MapClusterCellRow> cells = propertyMapper.selectClusterCells(
                clusters(typeFilter(null, PropertyType.OFFICETEL), GRID_BOX));

        assertThat(cells).extracting(MapClusterCellRow::count).containsExactly(2L);
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

    // ---------- 최신 판정 없는 매물(매물 갱신 배치 RISK-08) ----------
    // 공유 컨테이너에 다른 테스트가 커밋한 매물이 있을 수 있다. 식별자는 늘어나기만 하므로, 이 테스트가 넣은 첫 매물의 바로
    // 앞 식별자를 커서로 주면 이 테스트의 픽스처만 본다.

    @Test
    @DisplayName("미판정: 최신 판정이 있는 매물은 빠지고, 판정이 없거나 최신이 아닌 판정만 있는 매물은 식별자 순으로 나온다")
    void unanalyzedExcludesOnlyLatestAnalysis() {
        long none = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long latest = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long superseded = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        insertRisk(latest, "SAFE", "60.00", true, false);
        insertRisk(superseded, "DANGER", "95.00", false, false);

        List<Long> ids = propertyMapper.selectUnanalyzedPropertyIds(new UnanalyzedPropertyCondition(none - 1, 100));

        assertThat(ids).containsExactly(none, superseded);
    }

    @Test
    @DisplayName("미판정: 커서 다음부터 limit 건만, 마지막 페이지는 limit 보다 적게 나온다")
    void unanalyzedPagesByIdCursor() {
        long first = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long second = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long third = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);

        List<Long> firstPage = propertyMapper.selectUnanalyzedPropertyIds(new UnanalyzedPropertyCondition(first - 1, 2));
        List<Long> lastPage = propertyMapper.selectUnanalyzedPropertyIds(
                new UnanalyzedPropertyCondition(firstPage.getLast(), 2));

        assertThat(firstPage).containsExactly(first, second);
        assertThat(lastPage).containsExactly(third);
    }

    @Test
    @DisplayName("미판정: 커서가 없으면 처음부터 읽는다 — 이 테스트가 넣은 매물은 대상에 든다")
    void unanalyzedWithoutCursorStartsFromBeginning() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long count = jdbc.queryForObject("SELECT count(*) FROM property", Long.class);

        List<Long> ids = propertyMapper.selectUnanalyzedPropertyIds(
                new UnanalyzedPropertyCondition(null, (int) count));

        assertThat(ids).contains(id).isSorted();
    }

    // ---------- 재분석 대기 매물(매물 갱신 배치 RISK-08, V18) ----------
    // 위 미판정 조회와 같이 이 테스트가 넣은 첫 매물의 바로 앞 식별자를 커서로 준다.

    @Test
    @DisplayName("재분석 대기: 표시가 서고 최신 판정이 있는 매물만 식별자 순으로 나온다 — 표시만 선 새 매물 · 판정만 있는 매물은 빠진다")
    void priceChangedSelectsOnlyPendingAnalyzedProperties() {
        long pendingAnalyzed = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long notPending = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long pendingUnanalyzed = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long pendingSuperseded = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long pendingAnalyzed2 = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        insertRisk(pendingAnalyzed, "SAFE", "60.00", true, false);
        insertRisk(notPending, "SAFE", "60.00", true, false);
        insertRisk(pendingSuperseded, "DANGER", "95.00", false, false);
        insertRisk(pendingAnalyzed2, "SAFE", "60.00", true, false);
        markReanalysisPending(pendingAnalyzed);
        markReanalysisPending(pendingUnanalyzed);
        markReanalysisPending(pendingSuperseded);
        markReanalysisPending(pendingAnalyzed2);

        List<Long> ids = propertyMapper.selectReanalysisPendingPropertyIds(
                new ReanalysisPendingPropertyCondition(pendingAnalyzed - 1, 100));

        assertThat(ids).containsExactly(pendingAnalyzed, pendingAnalyzed2);
    }

    @Test
    @DisplayName("재분석 대기: 커서 다음부터 limit 건만, 마지막 페이지는 limit 보다 적게 나온다")
    void priceChangedPagesByIdCursor() {
        long first = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long second = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long third = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        List.of(first, second, third).forEach(id -> {
            insertRisk(id, "SAFE", "60.00", true, false);
            markReanalysisPending(id);
        });

        List<Long> firstPage = propertyMapper.selectReanalysisPendingPropertyIds(
                new ReanalysisPendingPropertyCondition(first - 1, 2));
        List<Long> lastPage = propertyMapper.selectReanalysisPendingPropertyIds(
                new ReanalysisPendingPropertyCondition(firstPage.getLast(), 2));

        assertThat(firstPage).containsExactly(first, second);
        assertThat(lastPage).containsExactly(third);
    }

    @Test
    @DisplayName("재분석 대기: 커서가 없으면 처음부터 읽는다 — 이 테스트가 넣은 매물은 대상에 든다")
    void priceChangedWithoutCursorStartsFromBeginning() {
        long id = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        insertRisk(id, "SAFE", "60.00", true, false);
        markReanalysisPending(id);
        long count = jdbc.queryForObject("SELECT count(*) FROM property", Long.class);

        List<Long> ids = propertyMapper.selectReanalysisPendingPropertyIds(
                new ReanalysisPendingPropertyCondition(null, (int) count));

        assertThat(ids).contains(id).isSorted();
    }

    @Test
    @DisplayName("두 갈래: 표시가 선 새 매물은 미판정에만, 표시가 선 판정 있는 매물은 재분석 대기에만 나온다 — 겹치지 않는다")
    void branchesDoNotOverlapForPendingProperties() {
        long pendingNew = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long pendingAnalyzed = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        long plainNew = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 1L, 0L, 37.50, 126.80, T0);
        insertRisk(pendingAnalyzed, "SAFE", "60.00", true, false);
        markReanalysisPending(pendingNew);
        markReanalysisPending(pendingAnalyzed);

        List<Long> unanalyzed = propertyMapper.selectUnanalyzedPropertyIds(
                new UnanalyzedPropertyCondition(pendingNew - 1, 100));
        List<Long> priceChanged = propertyMapper.selectReanalysisPendingPropertyIds(
                new ReanalysisPendingPropertyCondition(pendingNew - 1, 100));

        assertThat(unanalyzed).containsExactly(pendingNew, plainNew);
        assertThat(priceChanged).containsExactly(pendingAnalyzed);
    }

    private void markReanalysisPending(long propertyId) {
        jdbc.update("UPDATE property SET is_reanalysis_pending = TRUE WHERE property_id = ?", propertyId);
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

    /** 자치구를 인자로 받는 목록 조건 — 자치구 없는 갈래를 보려고 {@link #list} 대신 쓴다. */
    private static PropertySearchCondition listBy(DistrictCountRequest f, PropertySortKey sortKey,
            boolean ascending, BigDecimal lastDebtRatio, Long lastId, int limit) {
        BigDecimal nullDebtRatio = new BigDecimal(ascending ? "1000" : "-1000");
        return PropertySearchCondition.ofList(f, sortKey, ascending, nullDebtRatio,
                null, lastDebtRatio, null, lastId, limit);
    }

    /** 보증금순 목록 조건 — 자치구 · 필터를 인자로 받고 보증금 커서를 건다. */
    private static PropertySearchCondition depositListBy(DistrictCountRequest f, boolean ascending,
            Long lastDeposit, Long lastId, int limit) {
        return PropertySearchCondition.ofList(f, PropertySortKey.DEPOSIT, ascending,
                new BigDecimal(ascending ? "1000" : "-1000"), lastDeposit, null, null, lastId, limit);
    }

    /** 등록일순 목록 조건 — 자치구 · 필터를 인자로 받고 등록일 커서를 건다. */
    private static PropertySearchCondition registeredListBy(DistrictCountRequest f, boolean ascending,
            LocalDateTime lastRegisteredAt, Long lastId, int limit) {
        return PropertySearchCondition.ofList(f, PropertySortKey.REGISTERED_AT, ascending,
                new BigDecimal(ascending ? "1000" : "-1000"), null, null, lastRegisteredAt, lastId, limit);
    }

    // ---------- 자치구 투영(갱신 적재, #383) ----------

    @Test
    @DisplayName("자연키 투영: 엔티티 경로(Property::naturalKey)와 같은 집합이다")
    void naturalKeyProjectionMatchesEntityPath() {
        insertProjected(D1, "주소A", "59.90", 3, 100L, 0L, "11680");
        insertProjected(D1, "주소B", "84.9", 7, 200L, 50L, null);
        insertProjected(D1, "주소C", "33.00", null, 300L, 0L, "11680");

        List<PropertyNaturalKey> projected = propertyMapper.selectNaturalKeysByDistrict(D1);

        assertThat(projected).hasSize(3);
        assertThat(projected).containsExactlyInAnyOrderElementsOf(entityNaturalKeys(D1));
    }

    @Test
    @DisplayName("시세 투영: 엔티티 경로가 만들던 자연키 · 시세 · 대장 키 없음 값과 한 행씩 같다")
    void loadedPriceProjectionMatchesEntityPath() {
        insertProjected(D1, "주소A", "59.90", 3, 100L, 0L, "11680");
        insertProjected(D1, "주소B", "84.9", 7, 200L, 50L, null);

        List<LoadedPriceRow> projected = propertyMapper.selectLoadedPricesByDistrict(D1);

        List<LoadedPriceRow> expected = entityProperties(D1).stream()
                .map(PropertyMapperTest::loadedRowOf).toList();
        assertThat(projected).hasSize(2);
        assertThat(projected).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("투영: 면적 소수 자릿수가 정규화된다 - 84.9 는 84.90, 셋째 자리 입력은 DB 가 둘째 자리로 반올림한 값")
    void projectionAreaScaleIsNormalized() {
        insertProjected(D1, "주소B", "84.9", 7, 200L, 50L, null);
        insertProjected(D1, "주소D", "84.904", 7, 201L, 50L, null);

        List<PropertyNaturalKey> projected = propertyMapper.selectNaturalKeysByDistrict(D1);

        assertThat(projected).extracting(PropertyNaturalKey::areaSqm)
                .containsExactlyInAnyOrder(new BigDecimal("84.90"), new BigDecimal("84.90"));
        assertThat(projected).containsExactlyInAnyOrderElementsOf(entityNaturalKeys(D1));
        assertThat(propertyMapper.selectLoadedPricesByDistrict(D1))
                .allSatisfy(row -> assertThat(row.naturalKey().areaSqm()).isEqualTo(new BigDecimal("84.90")));
    }

    @Test
    @DisplayName("투영: 대장 키 없음은 시군구 코드가 비었을 때만 참이다")
    void projectionLedgerKeyMissingOnlyWhenSigunguCodeIsNull() {
        long keyed = insertProjected(D1, "주소A", "59.90", 3, 100L, 0L, "11680");
        long keyless = insertProjected(D1, "주소B", "84.90", 7, 200L, 50L, null);

        List<LoadedPriceRow> rows = propertyMapper.selectLoadedPricesByDistrict(D1);

        assertThat(rows).filteredOn(row -> row.propertyId() == keyed).singleElement()
                .extracting(LoadedPriceRow::ledgerKeyMissing).isEqualTo(false);
        assertThat(rows).filteredOn(row -> row.propertyId() == keyless).singleElement()
                .extracting(LoadedPriceRow::ledgerKeyMissing).isEqualTo(true);
    }

    @Test
    @DisplayName("투영: 다른 자치구의 매물은 두 조회 모두에서 제외된다")
    void projectionExcludesOtherDistrict() {
        insertProjected(D1, "주소A", "59.90", 3, 100L, 0L, "11680");
        long other = insertProjected(D2, "주소Z", "59.90", 3, 100L, 0L, "11680");

        assertThat(propertyMapper.selectNaturalKeysByDistrict(D1)).hasSize(1)
                .noneMatch(key -> "주소Z".equals(key.address()));
        assertThat(propertyMapper.selectLoadedPricesByDistrict(D1)).hasSize(1)
                .noneMatch(row -> row.propertyId() == other);
    }

    @Test
    @DisplayName("투영: 층이 null 인 매물도 엔티티 경로와 같은 자연키(층 null)로 읽힌다")
    void projectionNullFloorMatchesEntityPath() {
        insertProjected(D1, "주소C", "33.00", null, 300L, 0L, "11680");

        List<PropertyNaturalKey> projected = propertyMapper.selectNaturalKeysByDistrict(D1);

        assertThat(projected).singleElement().satisfies(key -> assertThat(key.floor()).isNull());
        assertThat(projected).containsExactlyElementsOf(entityNaturalKeys(D1));
        assertThat(propertyMapper.selectLoadedPricesByDistrict(D1)).singleElement()
                .satisfies(row -> assertThat(row.floor()).isNull());
    }

    private List<Property> entityProperties(String district) {
        return entityManager.createQuery("SELECT p FROM Property p WHERE p.district = :district", Property.class)
                .setParameter("district", district).getResultList();
    }

    private List<PropertyNaturalKey> entityNaturalKeys(String district) {
        return entityProperties(district).stream().map(Property::naturalKey).toList();
    }

    /** 지금까지 PropertyLoadWriter 가 엔티티로 만들던 값과 같은 식. */
    private static LoadedPriceRow loadedRowOf(Property property) {
        return new LoadedPriceRow(property.getAddress(), property.getAreaSqm(), property.getFloor(),
                property.getDeposit(), property.getMonthlyRent(), property.getPropertyId(),
                property.getMarketPrice(), property.getPriceType(), property.getPriceDate(),
                property.ledgerKey() == null);
    }

    private long insertProjected(String district, String address, String area, Integer floor, long deposit,
            long monthlyRent, String sigunguCode) {
        boolean keyed = sigunguCode != null;
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, sigungu_code, bjdong_code, bun, ji)
                VALUES (?, ?, '김임대', ?, ?, ?, ?, ?, 300000000, 'ACTUAL_TRANSACTION',
                    DATE '2026-06-30', ?, ?, 37.5, 126.8, ?, ?, ?, ?)
                RETURNING property_id
                """, Long.class,
                address, district, codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"),
                codeId("PROPERTY_TYPE", "APARTMENT"), codeId("PROPERTY_STATUS", "AVAILABLE"),
                deposit, monthlyRent, new BigDecimal(area), floor, sigunguCode,
                keyed ? "10100" : null, keyed ? "0100" : null, keyed ? "0001" : null);
    }

    /** D1 로 좁히고 계약 유형 · 매물 유형만 거는 필터. */
    private static DistrictCountRequest typeFilter(ContractType contractType, PropertyType propertyType) {
        return new DistrictCountRequest(D1, contractType, null, null, null, propertyType, null, null, null);
    }

    /**
     * 계약 · 매물 유형 조합 넷을 D1 에 넣고 식별자를 [전세·아파트, 월세·아파트, 전세·오피스텔, 월세·오피스텔] 순으로
     * 돌려준다. 보증금과 등록일은 식별자 순과 같은 방향으로 늘어 어느 정렬에서도 순서가 같다.
     */
    private long[] insertTypeMatrix() {
        long a = insertProperty(D1, "DEPOSIT_ONLY", "APARTMENT", 100L, 0L, 37.5, 126.8, T0);
        long b = insertProperty(D1, "MONTHLY_RENT", "APARTMENT", 200L, 500_000L, 37.5, 126.8, T0.plusDays(1));
        long c = insertProperty(D1, "DEPOSIT_ONLY", "OFFICETEL", 300L, 0L, 37.5, 126.8, T0.plusDays(2));
        long d = insertProperty(D1, "MONTHLY_RENT", "OFFICETEL", 400L, 500_000L, 37.5, 126.8, T0.plusDays(3));
        insertRisk(a, "SAFE", "10.00", true, false);
        insertRisk(b, "SAFE", "20.00", true, false);
        insertRisk(c, "SAFE", "30.00", true, false);
        insertRisk(d, "SAFE", "40.00", true, false);
        return new long[] {a, b, c, d};
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

    /**
     * 분석 한 건. FK 가 요구하는 표제부 · 건축물대장 최소 행을 함께 넣는다. 최신이면 매물의 최신 판정 열(V22)도 같은 값으로 맞춘다.
     *
     * <p>표제부 · 대장은 매물당 하나다(property_id UNIQUE — V4 · V5). 한 매물에 분석을 두 번 넣는 테스트가 있어 이미
     * 있으면 그 행을 쓴다.
     */
    private void insertRisk(long propertyId, String grade, String leaseRatio, boolean latest, boolean seniorDebt) {
        Long registryId = jdbc.queryForObject("""
                INSERT INTO building_registry (property_id, building_purpose, data_source) VALUES (?, '공동주택', 'MOCK')
                ON CONFLICT (property_id) DO UPDATE SET building_purpose = EXCLUDED.building_purpose
                RETURNING registry_id
                """, Long.class, propertyId);
        Long ledgerId = jdbc.queryForObject("""
                INSERT INTO building_ledger (property_id, ledger_address, owner_name, building_purpose, building_area,
                    data_source)
                VALUES (?, '주소', '김임대', '공동주택', 42.50, 'MOCK')
                ON CONFLICT (property_id) DO UPDATE SET building_purpose = EXCLUDED.building_purpose
                RETURNING ledger_id
                """, Long.class, propertyId);
        jdbc.update("""
                INSERT INTO risk_analysis (property_id, registry_id, ledger_id, lease_ratio, risk_grade,
                    is_latest, insurance_eligible_yn)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                """, propertyId, registryId, ledgerId, new BigDecimal(leaseRatio), grade, latest);
        // 최신 판정은 판정 기록이 같은 트랜잭션에서 매물의 비정규화 열(V22)에도 옮긴다 — 그 쓰기를 흉내 낸다. 매퍼는 이 열을 읽는다.
        if (latest) {
            setPropertyRisk(propertyId, grade, leaseRatio);
        }
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

    /** 매물의 최신 판정 비정규화 열(V22)만 바꾼다. 판정 표와 일부러 다르게 두어 매퍼가 어느 쪽을 읽는지 가를 때도 쓴다. */
    private void setPropertyRisk(long propertyId, String grade, String leaseRatio) {
        jdbc.update("UPDATE property SET risk_grade = ?, lease_ratio = ? WHERE property_id = ?",
                grade, leaseRatio == null ? null : new BigDecimal(leaseRatio), propertyId);
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('시험', 800) RETURNING user_id", Long.class);
    }
}
