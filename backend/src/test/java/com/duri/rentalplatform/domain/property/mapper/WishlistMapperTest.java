package com.duri.rentalplatform.domain.property.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.dto.condition.WishlistCondition;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WishlistMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. 사용자를 테스트마다 새로 만들어 조회가 그 사용자로 좁혀진다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class WishlistMapperTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 25, 11, 20, 0);

    @Autowired
    WishlistMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
    }

    @Test
    @DisplayName("모든 필드가 채워지고 등급은 최신 분석의 것, 등록 시각은 저장한 서울 벽시계와 같은 시각이다")
    void mapsAllFieldsFromLatestAnalysis() {
        long propertyId = insertProperty(230_000_000L);
        insertRisk(propertyId, "DANGER", null, false);
        insertRisk(propertyId, "SAFE", "CAUTION", true);
        long wishId = insertWish(userId, propertyId, T0);

        List<WishlistRow> rows = mapper.selectWishlist(new WishlistCondition(userId, null, 21));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.wishId()).isEqualTo(wishId);
            assertThat(row.propertyId()).isEqualTo(propertyId);
            assertThat(row.district()).isEqualTo("테스트1구");
            assertThat(row.deposit()).isEqualTo(230_000_000L);
            assertThat(row.riskGrade()).isEqualTo(RiskGrade.SAFE);
            assertThat(row.previousGrade()).isEqualTo(RiskGrade.CAUTION);
            assertThat(row.addedAt().toInstant()).isEqualTo(T0.toInstant(ZoneOffset.ofHours(9)));
        });
    }

    @Test
    @DisplayName("분석 전 매물도 목록에 남고 두 등급은 null 이다")
    void unanalyzedPropertyHasNullGrades() {
        insertWish(userId, insertProperty(1L), T0);

        assertThat(mapper.selectWishlist(new WishlistCondition(userId, null, 21)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.riskGrade()).isNull();
                    assertThat(row.previousGrade()).isNull();
                });
    }

    @Test
    @DisplayName("다른 사용자의 관심 매물은 나오지 않는다")
    void onlyOwnWishes() {
        long other = insertUser();
        insertWish(other, insertProperty(1L), T0);

        assertThat(mapper.selectWishlist(new WishlistCondition(userId, null, 21))).isEmpty();
    }

    @Test
    @DisplayName("커서: wishId 내림차순으로 첫 · 중간 · 마지막 페이지가 겹치지 않고 이어진다")
    void cursorPages() {
        long a = insertWish(userId, insertProperty(1L), T0);
        long b = insertWish(userId, insertProperty(2L), T0);
        long c = insertWish(userId, insertProperty(3L), T0);

        assertThat(ids(mapper.selectWishlist(new WishlistCondition(userId, null, 2)))).containsExactly(c, b);
        assertThat(ids(mapper.selectWishlist(new WishlistCondition(userId, b, 2)))).containsExactly(a);
        assertThat(mapper.selectWishlist(new WishlistCondition(userId, a, 2))).isEmpty();
    }

    private static List<Long> ids(List<WishlistRow> rows) {
        return rows.stream().map(WishlistRow::wishId).toList();
    }

    private long insertWish(long user, long propertyId, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition, created_at)
                VALUES (?, ?, TRUE, 'RISK_AND_REGISTRY', ?)
                RETURNING wish_id
                """, Long.class, user, propertyId, createdAt);
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject(
                "SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private long insertProperty(long deposit) {
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, registered_at)
                VALUES ('서울특별시 테스트1구 시험로 1', '테스트1구', '김임대', ?, ?, ?, ?, 0, 340000000,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, 37.5, 126.8, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), deposit, T0);
    }

    /** 분석 한 건. FK 가 요구하는 표제부 · 건축물대장 최소 행을 함께 넣는다(매물당 하나라 있으면 그 행). */
    private void insertRisk(long propertyId, String grade, String previousGrade, boolean latest) {
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
                    previous_grade, is_latest)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, propertyId, registryId, ledgerId, new BigDecimal("68.00"), grade, previousGrade, latest);
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('시험', 800) RETURNING user_id", Long.class);
    }
}
