package com.duri.rentalplatform.domain.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.risk.dto.response.RegistryResponse;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.domain.risk.service.RegistryCommandService;
import com.duri.rentalplatform.domain.risk.service.RegistryQueryService;
import com.duri.rentalplatform.domain.risk.vo.RegistryHeaderRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * {@link RegistryMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. SELECT 의 컬럼 순서를 record 컴포넌트 순서와 일부러
 * 어긋나게 두었다 — 순서가 같으면 이름 기반 생성자 매핑이 꺼져 있어도 통과한다.
 *
 * <p>마지막 테스트는 매퍼 앞의 수집 경로(Mock 클라이언트 → JPA 저장)를 같은 스키마로 한 번 통과시킨다. 엔티티의
 * 컬럼 매핑이 INSERT 로 실제 맞는지는 기동 시 {@code validate} 만으로 드러나지 않는다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class RegistryMapperTest {

    private static final LocalDateTime COLLECTED = LocalDateTime.of(2026, 7, 29, 3, 0, 0);

    @Autowired
    RegistryMapper registryMapper;

    @Autowired
    RegistryCommandService registryCommandService;

    @Autowired
    RegistryQueryService registryQueryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("표제부: 매물 ID · 수집 시각 · 출처가 매핑되고 수집 시각은 서울 벽시계 시각 그대로의 시점이다")
    void headerMapsAllFields() {
        long propertyId = insertProperty();
        insertRegistry(propertyId);

        RegistryHeaderRow header = registryMapper.selectHeader(propertyId);

        assertThat(header.propertyId()).isEqualTo(propertyId);
        assertThat(header.dataSource()).isEqualTo(RegistryDataSource.MOCK);
        assertThat(header.collectedAt().toInstant()).isEqualTo(COLLECTED.toInstant(ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("수집하지 않은 매물은 표제부가 null 이고 갑구 · 을구가 비어 있다")
    void uncollectedPropertyIsEmpty() {
        long propertyId = insertProperty();

        assertThat(registryMapper.selectHeader(propertyId)).isNull();
        assertThat(registryMapper.selectOwnerships(propertyId)).isEmpty();
        assertThat(registryMapper.selectMortgages(propertyId)).isEmpty();
    }

    @Test
    @DisplayName("갑구: 모든 필드가 매핑되고 접수일 → 순위번호 순이다")
    void ownershipsMapAndSort() {
        long propertyId = insertProperty();
        long registryId = insertRegistry(propertyId);
        insertOwnership(registryId, 3, "SEIZURE", "○○세무서", LocalDate.of(2021, 5, 3), "압류", true);
        insertOwnership(registryId, 2, "OWNERSHIP_TRANSFER", "김임대", LocalDate.of(2019, 3, 11), "매매", true);
        insertOwnership(registryId, 1, "OWNERSHIP_PRESERVATION", "이보존", LocalDate.of(2010, 1, 5), null, false);
        // 같은 접수일이면 순위번호가 가른다. 넣은 순서를 뒤집어 식별자 순이 아님을 확인한다.
        insertOwnership(registryId, 5, "TRUST", "○○자산신탁 주식회사", LocalDate.of(2022, 8, 1), "신탁", true);
        insertOwnership(registryId, 4, "PROVISIONAL_SEIZURE", "○○캐피탈 주식회사", LocalDate.of(2022, 8, 1),
                "가압류결정", true);
        // 다른 매물의 이력은 섞이지 않는다.
        long otherRegistry = insertRegistry(insertProperty());
        insertOwnership(otherRegistry, 1, "OWNERSHIP_PRESERVATION", "남의집", LocalDate.of(2000, 1, 1), null, true);

        List<RegistryResponse.Ownership> rows = registryMapper.selectOwnerships(propertyId);

        assertThat(rows).extracting(RegistryResponse.Ownership::rankNo).containsExactly(1, 2, 3, 4, 5);
        RegistryResponse.Ownership transfer = rows.get(1);
        assertThat(transfer.rightType()).isEqualTo(OwnershipRightType.OWNERSHIP_TRANSFER);
        assertThat(transfer.holderName()).isEqualTo("김임대");
        assertThat(transfer.receivedDate()).isEqualTo(LocalDate.of(2019, 3, 11));
        assertThat(transfer.cause()).isEqualTo("매매");
        assertThat(transfer.isActive()).isTrue();
        assertThat(rows.get(0).isActive()).isFalse();
        assertThat(rows.get(0).cause()).isNull();
    }

    @Test
    @DisplayName("을구: 모든 필드가 매핑되고 접수일 → 순위번호 순이다")
    void mortgagesMapAndSort() {
        long propertyId = insertProperty();
        long registryId = insertRegistry(propertyId);
        insertMortgage(registryId, 2, "○○은행", 60_000_000L, LocalDate.of(2021, 1, 4), true);
        insertMortgage(registryId, 1, "△△은행", 250_000_000L, LocalDate.of(2019, 3, 11), false);
        insertMortgage(registryId, 4, "□□저축은행", 30_000_000L, LocalDate.of(2023, 6, 1), true);
        insertMortgage(registryId, 3, "○○은행", 10_000_000L, LocalDate.of(2023, 6, 1), true);

        List<RegistryResponse.Mortgage> rows = registryMapper.selectMortgages(propertyId);

        assertThat(rows).extracting(RegistryResponse.Mortgage::rankNo, RegistryResponse.Mortgage::creditor,
                        RegistryResponse.Mortgage::maxClaimAmount, RegistryResponse.Mortgage::receivedDate,
                        RegistryResponse.Mortgage::isActive)
                .containsExactly(
                        tuple(1, "△△은행", 250_000_000L, LocalDate.of(2019, 3, 11), false),
                        tuple(2, "○○은행", 60_000_000L, LocalDate.of(2021, 1, 4), true),
                        tuple(3, "○○은행", 10_000_000L, LocalDate.of(2023, 6, 1), true),
                        tuple(4, "□□저축은행", 30_000_000L, LocalDate.of(2023, 6, 1), true));
    }

    @Test
    @DisplayName("한 매물에 표제부를 두 번 넣으면 UNIQUE 위반이다")
    void registryIsUniquePerProperty() {
        long propertyId = insertProperty();
        insertRegistry(propertyId);

        assertThatThrownBy(() -> insertRegistry(propertyId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("수집 경로: Mock 등기를 JPA 로 저장한 뒤 매퍼로 같은 건수가 읽히고, 두 번째 수집은 아무것도 더하지 않는다")
    void collectThroughMockClientThenQuery() {
        long propertyId = insertProperty();

        registryCommandService.collectIfAbsent(propertyId);
        RegistryResponse first = registryQueryService.getRegistry(propertyId);
        registryCommandService.collectIfAbsent(propertyId);
        RegistryResponse second = registryQueryService.getRegistry(propertyId);

        assertThat(first.dataSource()).isEqualTo(RegistryDataSource.MOCK);
        assertThat(first.ownerships()).isNotEmpty();
        assertThat(first.collectedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM building_registry WHERE property_id = ?",
                Long.class, propertyId)).isEqualTo(1L);
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
                VALUES ('서울특별시 등기시험구 시험로 1', '등기시험구', '김임대', ?, ?, ?, 230000000, 0, 340000000,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), new BigDecimal("37.5"), new BigDecimal("126.8"));
    }

    private long insertRegistry(long propertyId) {
        return jdbc.queryForObject("""
                INSERT INTO building_registry (property_id, building_purpose, data_source, updated_at)
                VALUES (?, '공동주택', 'MOCK', ?) RETURNING registry_id
                """, Long.class, propertyId, COLLECTED);
    }

    private void insertOwnership(long registryId, int rankNo, String rightType, String holder, LocalDate date,
            String cause, boolean current) {
        jdbc.update("""
                INSERT INTO ownership_history (registry_id, rank_no, right_type, owner_name, ownership_date,
                    registration_cause, is_current)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, registryId, rankNo, rightType, holder, date, cause, current);
    }

    private void insertMortgage(long registryId, int priorityNo, String creditor, long maxBond, LocalDate date,
            boolean active) {
        jdbc.update("""
                INSERT INTO mortgage_history (registry_id, priority_no, right_type, receipt_date, mortgage_creditor,
                    max_bond_amount, is_active)
                VALUES (?, ?, 'MORTGAGE', ?, ?, ?, ?)
                """, registryId, priorityNo, date, creditor, maxBond, active);
    }
}
