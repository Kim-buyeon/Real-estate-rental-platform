package com.duri.rentalplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1 초기 스키마 마이그레이션 검증.
 *
 * <p>실제 PostgreSQL 17 컨테이너를 기동해 Flyway V1을 적용하고, {@code ddl-auto=validate}가
 * 통과하는지(= 컨텍스트 로드 성공)와 스키마 구조가 database.md §3의 정의와 일치하는지 확인한다.
 * H2가 아닌 실제 DB로 검증한다(testing.md §1.1 통합 테스트).
 *
 * <p>기대값 근거는 {@code db/migration/V1__init_schema.sql}이며, 아래 상수는 그 SQL을 세어 얻은 값이다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

    /** V1__init_schema.sql이 생성하는 BASE TABLE 수. flyway_schema_history는 제외한다. */
    private static final int EXPECTED_TABLE_COUNT = 30;

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
    @DisplayName("V1 적용 후 public 스키마의 BASE TABLE 수는 30개다 (flyway 이력 테이블 제외)")
    void migratesExactlyThirtyTables() {
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
}
