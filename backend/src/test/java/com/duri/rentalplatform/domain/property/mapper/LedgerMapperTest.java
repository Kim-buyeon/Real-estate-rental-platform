package com.duri.rentalplatform.domain.property.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.property.service.LedgerQueryService;
import com.duri.rentalplatform.domain.property.vo.LedgerRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LedgerMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. SELECT 의 컬럼 순서를 record 컴포넌트 순서와 일부러 어긋나게
 * 두었다 — 순서가 같으면 이름 기반 생성자 매핑이 꺼져 있어도 통과한다.
 *
 * <p>마지막 테스트는 매퍼 앞의 수집 경로(Mock 클라이언트 → JPA 저장)를 같은 스키마로 한 번 통과시킨다. 엔티티의 컬럼
 * 매핑이 INSERT 로 실제 맞는지는 기동 시 {@code validate} 만으로 드러나지 않는다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class LedgerMapperTest {

    private static final LocalDateTime COLLECTED = LocalDateTime.of(2026, 7, 28, 2, 10, 0);

    @Autowired
    LedgerMapper ledgerMapper;

    @Autowired
    LedgerCommandService ledgerCommandService;

    @Autowired
    LedgerQueryService ledgerQueryService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BuildingLedgerRepository buildingLedgerRepository;

    @Autowired
    PropertyRepository propertyRepository;

    @Test
    @DisplayName("모든 필드가 매핑되고 수집 시각은 서울 벽시계 시각 그대로의 시점이다")
    void mapsAllFields() {
        long propertyId = insertProperty();
        insertLedger(propertyId, true);

        LedgerRow row = ledgerMapper.selectLedger(propertyId);

        assertThat(row.propertyId()).isEqualTo(propertyId);
        assertThat(row.mainPurpose()).isEqualTo("공동주택");
        assertThat(row.violationBuilding()).isTrue();
        assertThat(row.totalFloorArea()).isEqualByComparingTo("480.20");
        assertThat(row.exclusiveArea()).isEqualByComparingTo("42.50");
        assertThat(row.approvalDate()).isEqualTo(LocalDate.of(2015, 4, 18));
        assertThat(row.collectedAt().toInstant()).isEqualTo(COLLECTED.toInstant(ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("위반건축물이 아니면 false 로 돌아온다")
    void violationFalse() {
        long propertyId = insertProperty();
        insertLedger(propertyId, false);

        assertThat(ledgerMapper.selectLedger(propertyId).violationBuilding()).isFalse();
    }

    @Test
    @DisplayName("대장이 없는 매물은 매물 식별자만 차고 대장 항목은 전부 null 이며, 다른 매물의 대장이 섞이지 않는다")
    void propertyWithoutLedgerHasOnlyPropertyId() {
        long propertyId = insertProperty();
        insertLedger(insertProperty(), true);

        assertThat(ledgerMapper.selectLedger(propertyId))
                .isEqualTo(new LedgerRow(null, null, null, null, null, null, propertyId, null));
    }

    @Test
    @DisplayName("없는 매물은 행이 없다 — 서비스가 PROPERTY_NOT_FOUND 로 낸다")
    void missingPropertyIsNull() {
        assertThat(ledgerMapper.selectLedger(-1L)).isNull();
    }

    @Test
    @DisplayName("대장 조회: 뗄 대장이 없는 매물은 200 응답 형태로 대장 항목이 전부 null 이다")
    void queryWithoutLedgerYieldsNullFields() {
        long propertyId = insertProperty();

        LedgerResponse response = ledgerQueryService.getLedger(propertyId);

        assertThat(response).isEqualTo(new LedgerResponse(propertyId, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("한 매물에 대장을 두 번 넣으면 UNIQUE 위반이다")
    void ledgerIsUniquePerProperty() {
        long propertyId = insertProperty();
        insertLedger(propertyId, false);

        assertThatThrownBy(() -> insertLedger(propertyId, false)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("수집 경로: Mock 대장을 JPA 로 저장한 뒤 매퍼로 읽히고, 두 번째 수집은 아무것도 더하지 않는다")
    void collectThroughMockClientThenQuery() {
        long propertyId = insertProperty();

        ledgerCommandService.collectIfAbsent(propertyId);
        LedgerResponse first = ledgerQueryService.getLedger(propertyId);
        ledgerCommandService.collectIfAbsent(propertyId);
        LedgerResponse second = ledgerQueryService.getLedger(propertyId);

        assertThat(first.mainPurpose()).isEqualTo("공동주택");
        assertThat(first.isResidential()).isTrue();
        assertThat(first.exclusiveArea()).isEqualByComparingTo("42.50");
        assertThat(first.totalFloorArea()).isNotNull();
        assertThat(first.approvalDate()).isNotNull();
        assertThat(first.collectedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM building_ledger WHERE property_id = ?",
                Long.class, propertyId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT data_source FROM building_ledger WHERE property_id = ?",
                String.class, propertyId)).isEqualTo("MOCK");
    }

    // ---------- V17 — 확인하지 못한 값 · 대장 조회 키(PROP-04) ----------

    @Test
    @DisplayName("V17: 위반건축물 확인 불가(NULL)는 매퍼 · 명세 1.8 응답 모두 null 그대로다")
    void violationNullMapsToNull() {
        long propertyId = insertProperty();
        insertLedger(propertyId, null);

        assertThat(ledgerMapper.selectLedger(propertyId).violationBuilding()).isNull();
        assertThat(ledgerQueryService.getLedger(propertyId).violationBuilding()).isNull();
    }

    @Test
    @DisplayName("V17: 대장 없이 분석한 행은 risk_analysis.ledger_id 를 비워 저장할 수 있다")
    void riskAnalysisLedgerIdIsNullable() {
        long propertyId = insertProperty();
        long registryId = jdbc.queryForObject("""
                INSERT INTO building_registry (property_id, building_purpose, data_source) VALUES (?, '공동주택', 'MOCK')
                RETURNING registry_id
                """, Long.class, propertyId);

        jdbc.update("""
                INSERT INTO risk_analysis (property_id, registry_id, ledger_id, lease_ratio, risk_grade)
                VALUES (?, ?, NULL, 50.00, 'SAFE')
                """, propertyId, registryId);

        assertThat(jdbc.queryForObject("SELECT ledger_id FROM risk_analysis WHERE property_id = ?",
                Long.class, propertyId)).isNull();
    }

    @Test
    @DisplayName("V17: 건축HUB 가 주지 않는 소유자 · 건축면적 · 위반건축물을 비운 대장을 JPA 로 저장할 수 있다")
    void savesBuildingHubLedgerWithUnknownValues() {
        long propertyId = insertProperty();

        buildingLedgerRepository.saveAndFlush(BuildingLedger.collect(propertyId, "서울특별시 종로구 동망산길 19 (창신동)",
                null, "공동주택", "철근콘크리트구조", null, new BigDecimal("14544.66"), null, LocalDate.of(1992, 11, 25),
                null, LedgerDataSource.BUILDING_HUB));

        assertThat(jdbc.queryForObject("SELECT violation_yn FROM building_ledger WHERE property_id = ?",
                Boolean.class, propertyId)).isNull();
        assertThat(jdbc.queryForObject("SELECT data_source FROM building_ledger WHERE property_id = ?",
                String.class, propertyId)).isEqualTo("BUILDING_HUB");
    }

    @Test
    @DisplayName("V17: 위반건축물 컬럼에 기본값이 없다 — 값을 빠뜨린 저장이 「위반 아님」으로 남지 않는다")
    void violationHasNoDefault() {
        long propertyId = insertProperty();
        jdbc.update("""
                INSERT INTO building_ledger (property_id, ledger_address, building_purpose, data_source)
                VALUES (?, '서울특별시 대장시험구 시험로 1', '공동주택', 'BUILDING_HUB')
                """, propertyId);

        assertThat(jdbc.queryForObject("SELECT violation_yn FROM building_ledger WHERE property_id = ?",
                Boolean.class, propertyId)).isNull();
    }

    @Test
    @DisplayName("V17: 매물의 대장 조회 키는 넷이 전부 있거나 전부 없다 — 일부만 넣으면 CHECK 위반")
    void ledgerKeyIsAllOrNone() {
        long propertyId = insertProperty();

        jdbc.update("UPDATE property SET sigungu_code = '11110', bjdong_code = '17400', bun = '0702', ji = '0000'"
                + " WHERE property_id = ?", propertyId);
        assertThatThrownBy(() -> jdbc.update("UPDATE property SET ji = NULL WHERE property_id = ?", propertyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V17: 대장 조회 키를 JPA 로 채우면 컬럼에 그대로 저장되고 다시 읽힌다")
    void fillsLedgerKeyThroughJpa() {
        long propertyId = insertProperty();
        LedgerLookupKey key = new LedgerLookupKey("11110", "17400", "0702", "0000");

        Property property = propertyRepository.findById(propertyId).orElseThrow();
        assertThat(property.ledgerKey()).isNull();
        assertThat(property.fillLedgerKey(key)).isTrue();
        propertyRepository.flush();

        assertThat(jdbc.queryForObject("SELECT sigungu_code || bjdong_code || bun || ji FROM property"
                + " WHERE property_id = ?", String.class, propertyId)).isEqualTo("1111017400" + "07020000");
    }

    // ---------- 픽스처 ----------

    private long codeId(String group, String value) {
        return jdbc.queryForObject(
                "SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private long insertProperty() {
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude)
                VALUES ('서울특별시 대장시험구 시험로 1', '대장시험구', '김임대', ?, ?, ?, 230000000, 0, 340000000,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), new BigDecimal("37.5"), new BigDecimal("126.8"));
    }

    private void insertLedger(long propertyId, Boolean violation) {
        jdbc.update("""
                INSERT INTO building_ledger (property_id, ledger_address, owner_name, building_purpose, building_area,
                    total_floor_area, exclusive_area, approval_date, violation_yn, data_source, updated_at)
                VALUES (?, '서울특별시 대장시험구 시험로 1', '김임대', '공동주택', 160.07, 480.20, 42.50,
                    DATE '2015-04-18', ?, 'MOCK', ?)
                """, propertyId, violation, COLLECTED);
    }
}
