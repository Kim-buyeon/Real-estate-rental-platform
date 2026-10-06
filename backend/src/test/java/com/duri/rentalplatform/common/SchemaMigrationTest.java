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
    @DisplayName("V15: 매물은 자치구 + 좌표 · 자치구 + 등록순, 등기 이력 둘은 registry_id 인덱스다")
    void queryIndexes() {
        Map<String, String> indexDefs = jdbcTemplate.queryForList(
                        """
                        SELECT indexname, indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('idx_property_district_lat_lng', 'idx_property_district_registered',
                                            'idx_ownership_history_registry', 'idx_mortgage_history_registry')
                        """)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("indexname"), r -> (String) r.get("indexdef")));

        assertThat(indexDefs).hasSize(4);
        assertThat(indexDefs.get("idx_property_district_lat_lng")).contains("property", "(district, latitude, longitude)");
        assertThat(indexDefs.get("idx_property_district_registered"))
                .contains("property", "(district, registered_at DESC, property_id DESC)");
        assertThat(indexDefs.get("idx_ownership_history_registry")).contains("ownership_history", "(registry_id)");
        assertThat(indexDefs.get("idx_mortgage_history_registry")).contains("mortgage_history", "(registry_id)");
    }

    @Test
    @DisplayName("V19: 최신 분석 인덱스는 유일 + INCLUDE 커버링 부분 인덱스고, 신규 다섯 인덱스가 정의대로 있다")
    void indexOptimizationIndexes() {
        Map<String, String> indexDefs = jdbcTemplate.queryForList(
                        """
                        SELECT indexname, indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('uq_risk_analysis_latest', 'idx_mortgage_history_registry_active',
                                            'idx_property_registered', 'idx_wishlist_user_wish',
                                            'idx_risk_analysis_ledger', 'idx_wishlist_notification_wish')
                        """)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("indexname"), r -> (String) r.get("indexdef")));

        assertThat(indexDefs).hasSize(6);
        assertThat(indexDefs.get("uq_risk_analysis_latest"))
                .contains("CREATE UNIQUE INDEX", "risk_analysis", "(property_id)",
                        "INCLUDE (risk_id, risk_grade, lease_ratio, registry_id)", "WHERE", "is_latest");
        assertThat(indexDefs.get("idx_mortgage_history_registry_active"))
                .contains("mortgage_history", "(registry_id)", "WHERE", "is_active");
        assertThat(indexDefs.get("idx_property_registered"))
                .contains("property", "(registered_at DESC, property_id DESC)");
        assertThat(indexDefs.get("idx_wishlist_user_wish")).contains("wishlist", "(user_id, wish_id DESC)");
        assertThat(indexDefs.get("idx_risk_analysis_ledger")).contains("risk_analysis", "(ledger_id)");
        assertThat(indexDefs.get("idx_wishlist_notification_wish"))
                .contains("wishlist_notification", "(wish_id)");
    }

    @Test
    @DisplayName("V20: 전세가율 목록 인덱스는 최신 판정만 (lease_ratio, property_id) 순으로 담고 등급을 INCLUDE 로 둔다")
    void leaseRatioListIndex() {
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = 'ix_risk_analysis_latest_lease_ratio'", String.class);

        assertThat(def).contains("risk_analysis", "(lease_ratio, property_id)", "INCLUDE (risk_grade)",
                "WHERE", "is_latest", "lease_ratio IS NOT NULL");
        assertThat(def).doesNotContain("UNIQUE");
    }

    @Test
    @DisplayName("V22 ~ V29: 매물에 최신 판정 등급 · 전세가율 열이 NULL 허용으로 있고, 전세가율 필터 인덱스(V28)가 유효한 정의로 있으며 옛 전세가율 인덱스(V29 삭제)는 없다")
    void propertyLatestRiskColumnsAndIndexes() {
        Map<String, String> columns = jdbcTemplate.queryForList(
                        """
                        SELECT column_name, data_type || ':' || COALESCE(character_maximum_length::text, '')
                                   || ':' || COALESCE(numeric_precision::text, '') || ':' || COALESCE(numeric_scale::text, '')
                                   || ':' || is_nullable AS def
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'property'
                          AND column_name IN ('risk_grade', 'lease_ratio')
                        """)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("column_name"), r -> (String) r.get("def")));
        Map<String, String> indexDefs = jdbcTemplate.queryForList(
                        """
                        SELECT indexname, indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN ('ix_property_lease_ratio', 'ix_property_lease_ratio_filter',
                                            'idx_property_district_lat_lng', 'idx_property_district_lat_lng_cov')
                        """)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("indexname"), r -> (String) r.get("indexdef")));

        assertThat(columns).containsEntry("risk_grade", "character varying:10:::YES")
                .containsEntry("lease_ratio", "numeric::5:2:YES");
        // V29 가 V24 의 ix_property_lease_ratio 를 지웠고, V28 의 ix_property_lease_ratio_filter 가 그 키를 이어받는다.
        assertThat(indexDefs).containsOnlyKeys("ix_property_lease_ratio_filter", "idx_property_district_lat_lng");
        assertThat(indexDefs.get("ix_property_lease_ratio_filter"))
                .contains("property", "(lease_ratio, property_id)",
                        "INCLUDE (risk_grade, deposit, monthly_rent, area_sqm, contract_type_code_id, "
                                + "property_type_code_id, latitude, longitude)",
                        "WHERE", "lease_ratio IS NOT NULL")
                .doesNotContain("UNIQUE");
        assertThat(indexDefs.get("idx_property_district_lat_lng"))
                .contains("property", "(district, latitude, longitude)", "INCLUDE (property_id, risk_grade)");
        // V24 · V28 은 CONCURRENTLY(트랜잭션 밖)다 — 실패하면 INVALID 인덱스가 남으므로 유효 여부까지 본다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname IN ('ix_property_lease_ratio_filter', 'idx_property_district_lat_lng') AND i.indisvalid
                """, Integer.class)).isEqualTo(2);
        // 파일마다 따로 적용되어 각자 성공으로 이력에 있다.
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IN ('22', '23', '24', '25', '28', '29') "
                        + "AND success ORDER BY installed_rank", String.class))
                .containsExactly("22", "23", "24", "25", "28", "29");
    }

    @Test
    @DisplayName("V26: 보증금 목록 인덱스는 property 의 (deposit, property_id) 일반 인덱스이고 유효하며 이력에 성공으로 있다")
    void depositListIndex() {
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ix_property_deposit'",
                String.class);

        assertThat(def).contains("property", "(deposit, property_id)");
        assertThat(def).doesNotContain("UNIQUE").doesNotContain("WHERE").doesNotContain("INCLUDE");
        // CONCURRENTLY(트랜잭션 밖)라 실패하면 INVALID 인덱스가 남는다 — 유효 여부까지 본다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'ix_property_deposit'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version = '26' AND success", String.class))
                .containsExactly("26");
    }

    @Test
    @DisplayName("V27: 유형·등급·보증금 인덱스는 property 의 (property_type_code_id, risk_grade, deposit, property_id) 일반 인덱스이고 유효하며 이력에 성공으로 있다")
    void typeGradeDepositIndex() {
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = 'ix_property_type_grade_deposit'",
                String.class);

        assertThat(def).contains("property", "(property_type_code_id, risk_grade, deposit, property_id)");
        assertThat(def).doesNotContain("UNIQUE").doesNotContain("WHERE").doesNotContain("INCLUDE");
        // IF NOT EXISTS 라 같은 이름의 INVALID 인덱스가 남아 있으면 건너뛸 수 있다 — 유효 여부가 특히 중요하다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'ix_property_type_grade_deposit'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version = '27' AND success", String.class))
                .containsExactly("27");
    }

    @Test
    @DisplayName("V6: 위험 등급 기준은 CAUTION 경계가 깡통전세 선 이상이면 거부된다")
    void riskCriteriaRejectsNonMonotonicThresholds() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE risk_criteria SET caution_lease_ratio = negative_equity_ratio"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
