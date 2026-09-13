package com.duri.rentalplatform.domain.property.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
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
    @DisplayName("수집하지 않은 매물은 null 이고 다른 매물의 대장이 섞이지 않는다")
    void uncollectedPropertyIsNull() {
        long propertyId = insertProperty();
        insertLedger(insertProperty(), true);

        assertThat(ledgerMapper.selectLedger(propertyId)).isNull();
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

    private void insertLedger(long propertyId, boolean violation) {
        jdbc.update("""
                INSERT INTO building_ledger (property_id, ledger_address, owner_name, building_purpose, building_area,
                    total_floor_area, exclusive_area, approval_date, violation_yn, data_source, updated_at)
                VALUES (?, '서울특별시 대장시험구 시험로 1', '김임대', '공동주택', 160.07, 480.20, 42.50,
                    DATE '2015-04-18', ?, 'MOCK', ?)
                """, propertyId, violation, COLLECTED);
    }
}
