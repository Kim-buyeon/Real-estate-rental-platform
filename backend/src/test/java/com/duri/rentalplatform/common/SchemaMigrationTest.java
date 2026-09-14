package com.duri.rentalplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 스키마 마이그레이션 검증.
 *
 * <p>실제 PostgreSQL 17 컨테이너를 기동해 Flyway V1을 적용하고, {@code ddl-auto=validate}가
 * 통과하는지(= 컨텍스트 로드 성공)와 스키마 구조가 database.md §3의 정의와 일치하는지 확인한다.
 * H2가 아닌 실제 DB로 검증한다(testing.md §1.1 통합 테스트).
 *
 * <p>기대값 근거는 {@code db/migration/} 의 SQL 이며, 아래 상수는 그 SQL을 세어 얻은 값이다.
 * V1 이 30개, V6 이 criteria_change_history · risk_criteria 를 더한다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

    /** 마이그레이션이 생성하는 BASE TABLE 수(V1 30 + V6 2). flyway_schema_history는 제외한다. */
    private static final int EXPECTED_TABLE_COUNT = 32;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private List<String> baseTableNames() {
        return jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """,
                String.class);
    }

    @Test
    @DisplayName("마이그레이션 적용 후 public 스키마의 BASE TABLE 수는 32개다 (flyway 이력 테이블 제외)")
    void migratesExactlyThirtyTwoTables() {
        assertThat(baseTableNames()).hasSize(EXPECTED_TABLE_COUNT);
    }

    @Test
    @DisplayName("예약어 회피: users 테이블은 존재하고 user 테이블은 존재하지 않는다")
    void userTableIsCreatedAsUsersToAvoidReservedWord() {
        List<String> tables = baseTableNames();
        assertThat(tables).contains("users");
        assertThat(tables).doesNotContain("user");
    }

    @Test
    @DisplayName("자연키 UNIQUE 제약 uq_guarantee_criteria_provider가 guarantee_criteria에 존재한다")
    void naturalKeyUniqueConstraintExists() {
        List<String> uniqueConstraints = jdbcTemplate.queryForList(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'guarantee_criteria'
                  AND constraint_type = 'UNIQUE'
                """,
                String.class);
        assertThat(uniqueConstraints).contains("uq_guarantee_criteria_provider");
    }
    @Test
    @DisplayName("V7 시드: 보증기관 3사 기준과 위험 등급 기준 한 행이 들어 있다")
    void judgementCriteriaSeeded() {
        List<String> providers = jdbcTemplate.queryForList(
                "SELECT provider FROM guarantee_criteria ORDER BY provider", String.class);
        assertThat(providers).containsExactly("HF", "HUG", "SGI");

        Integer riskCriteriaRows = jdbcTemplate.queryForObject("SELECT count(*) FROM risk_criteria", Integer.class);
        assertThat(riskCriteriaRows).isEqualTo(1);
    }

    @Test
    @DisplayName("V10 시드: 전세자금대출 규제 한 행과 대표 상품 한 행이 들어 있고 LTV 컬럼은 없다")
    void jeonseLoanRegulationSeeded() {
        Map<String, Object> regulation = jdbcTemplate.queryForMap(
                "SELECT deposit_ratio_limit, guarantee_cap_no_house, guarantee_cap_one_house, dsr_limit,"
                        + " stress_dsr_rate FROM loan_regulation");
        assertThat(regulation.get("deposit_ratio_limit")).isEqualTo(new BigDecimal("80.00"));
        assertThat(regulation.get("guarantee_cap_no_house")).isEqualTo(400_000_000L);
        assertThat(regulation.get("guarantee_cap_one_house")).isEqualTo(180_000_000L);
        assertThat(regulation.get("dsr_limit")).isEqualTo(new BigDecimal("40.00"));
        assertThat(regulation.get("stress_dsr_rate")).isEqualTo(new BigDecimal("3.00"));

        Integer productRows = jdbcTemplate.queryForObject("SELECT count(*) FROM loan_product", Integer.class);
        assertThat(productRows).isEqualTo(1);

        Integer removedColumns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'loan_regulation'"
                        + " AND column_name IN ('ltv_limit', 'stress_dsr_limit')",
                Integer.class);
        assertThat(removedColumns).isZero();
    }

    @Test
    @DisplayName("V11: 관심 매물 유일 제약 uq_wishlist_user_property 가 (user_id, property_id) 에 있다")
    void wishlistUniqueConstraintExists() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'wishlist'
                  AND tc.constraint_type = 'UNIQUE'
                  AND tc.constraint_name = 'uq_wishlist_user_property'
                ORDER BY kcu.ordinal_position
                """,
                String.class);
        assertThat(columns).containsExactly("user_id", "property_id");
    }

    @Test
    @DisplayName("V12: 알림 구독 조건 세 컬럼은 NULL 허용, contract_type 은 20자, 유일 인덱스는 NULLS NOT DISTINCT 다")
    void notificationSubscriptionConditionsRelaxed() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'notification_subscription'
                  AND column_name IN ('target_district', 'contract_type', 'deposit_max')
                ORDER BY column_name
                """);
        assertThat(columns).extracting(c -> c.get("column_name"), c -> c.get("is_nullable"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("contract_type", "YES"),
                        org.assertj.core.groups.Tuple.tuple("deposit_max", "YES"),
                        org.assertj.core.groups.Tuple.tuple("target_district", "YES"));
        assertThat(columns.getFirst().get("character_maximum_length")).isEqualTo(20);

        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                        + " AND indexname = 'uq_notification_subscription_user_type_district'",
                String.class);
        assertThat(indexDef).contains("UNIQUE", "(user_id, subscription_type, target_district)", "NULLS NOT DISTINCT");
    }

    @Test
    @DisplayName("V13: 관심 매물 알림은 property_id NOT NULL · wish_id NULL 허용이고, 원천 행 삭제 시 SET NULL 이다")
    void notificationHistoryRetainedOnSourceDeletion() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                """
                SELECT table_name, column_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND ((table_name = 'wishlist_notification' AND column_name IN ('property_id', 'wish_id'))
                    OR (table_name IN ('property_notification', 'rate_notification')
                        AND column_name = 'subscription_id'))
                ORDER BY table_name, column_name
                """);
        assertThat(columns).extracting(c -> c.get("table_name"), c -> c.get("column_name"), c -> c.get("is_nullable"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("property_notification", "subscription_id", "YES"),
                        org.assertj.core.groups.Tuple.tuple("rate_notification", "subscription_id", "YES"),
                        org.assertj.core.groups.Tuple.tuple("wishlist_notification", "property_id", "NO"),
                        org.assertj.core.groups.Tuple.tuple("wishlist_notification", "wish_id", "YES"));

        List<Map<String, Object>> deleteRules = jdbcTemplate.queryForList(
                """
                SELECT constraint_name, delete_rule
                FROM information_schema.referential_constraints
                WHERE constraint_schema = 'public'
                  AND constraint_name IN ('wishlist_notification_wish_id_fkey',
                                          'property_notification_subscription_id_fkey',
                                          'rate_notification_subscription_id_fkey')
                ORDER BY constraint_name
                """);
        assertThat(deleteRules).extracting(r -> r.get("constraint_name"), r -> r.get("delete_rule"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("property_notification_subscription_id_fkey", "SET NULL"),
                        org.assertj.core.groups.Tuple.tuple("rate_notification_subscription_id_fkey", "SET NULL"),
                        org.assertj.core.groups.Tuple.tuple("wishlist_notification_wish_id_fkey", "SET NULL"));

        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_wishlist_property'",
                String.class);
        assertThat(indexDef).contains("wishlist", "(property_id)");
    }

    @Test
    @DisplayName("V14: 알림 목록 인덱스는 (user_id, notif_id DESC), 읽지 않은 수는 부분 인덱스, 상세 조인은 notif_id 다")
    void notificationListIndexes() {
        Map<String, String> indexDefs = jdbcTemplate.queryForList(
                        """
                        SELECT indexname, indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('idx_notification_user_notif', 'idx_notification_user_unread',
                                            'idx_wishlist_notification_notif')
                        """)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("indexname"), r -> (String) r.get("indexdef")));

        assertThat(indexDefs).hasSize(3);
        assertThat(indexDefs.get("idx_notification_user_notif")).contains("notification", "(user_id, notif_id DESC)");
        assertThat(indexDefs.get("idx_notification_user_unread")).contains("(user_id)", "WHERE", "is_read = false");
        assertThat(indexDefs.get("idx_wishlist_notification_notif")).contains("wishlist_notification", "(notif_id)");
    }

    @Test
    @DisplayName("V6: 위험 등급 기준은 CAUTION 경계가 깡통전세 선 이상이면 거부된다")
    void riskCriteriaRejectsNonMonotonicThresholds() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE risk_criteria SET caution_lease_ratio = negative_equity_ratio"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
