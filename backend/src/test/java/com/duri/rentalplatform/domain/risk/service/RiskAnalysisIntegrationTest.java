package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위험도 분석을 실제 PostgreSQL(Flyway 적용 스키마)과 Mock 수집 클라이언트로 한 번 통과시킨다.
 *
 * <p>기준 엔티티 7종 · 분석 엔티티의 컬럼 매핑이 SELECT · INSERT · UPDATE 로 실제 맞는지 본다 — 기동 시 {@code validate}
 * 만으로는 드러나지 않는다. 판정 기준은 V7 시드를 읽는다. 기준 테이블은 기관 UNIQUE 라 테스트가 따로 넣을 수 없고,
 * 여기서는 판정 값이 아니라 흐름과 저장 분기만 확인한다. 테스트마다 롤백한다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class RiskAnalysisIntegrationTest {

    @Autowired
    RiskAnalysisCommandService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("수집 → 판정 → 저장. 같은 결론으로 두 번 조회해도 분석 행은 하나다")
    void analyzesOnceForSameConclusion() {
        long propertyId = insertProperty(340_000_000L);

        RiskResponse first = service.analyze(propertyId);
        RiskResponse second = service.analyze(propertyId);

        assertThat(first.providers()).extracting(RiskResponse.Provider::provider)
                .containsExactly(GuaranteeProvider.HUG, GuaranteeProvider.HF, GuaranteeProvider.SGI);
        assertThat(first.analyzedAt()).isNotNull();
        assertThat(second.analyzedAt()).isEqualTo(first.analyzedAt());
        assertThat(countRows(propertyId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT risk_grade, risk_reason, is_latest, previous_grade FROM risk_analysis WHERE property_id = ?",
                propertyId);
        assertThat(row.get("risk_grade")).isEqualTo(first.riskGrade().name());
        assertThat(row.get("risk_reason")).isEqualTo(first.gradeReason().name());
        assertThat(row.get("is_latest")).isEqualTo(true);
        assertThat(row.get("previous_grade")).isNull();
    }

    @Test
    @DisplayName("시세가 바뀌어 결론이 달라지면 기존 행을 내리고 이전 등급을 담은 최신 행을 더한다")
    void changedConclusionAddsLatestRow() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.analyze(propertyId);

        // 보증금 2.3억 > 시세 1억 — 전세가율이 바뀌고 깡통전세다. 영속성 컨텍스트의 매물을 비워 새 시세를 읽게 한다.
        entityManager.flush();
        jdbc.update("UPDATE property SET market_price = 100000000 WHERE property_id = ?", propertyId);
        entityManager.clear();

        RiskResponse second = service.analyze(propertyId);
        entityManager.flush();

        assertThat(second.isNegativeEquity()).isTrue();
        assertThat(countRows(propertyId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_analysis WHERE property_id = ? AND is_latest", Integer.class, propertyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT previous_grade FROM risk_analysis WHERE property_id = ? AND is_latest", String.class,
                propertyId))
                .isEqualTo(first.riskGrade().name());
    }

    private int countRows(long propertyId) {
        return jdbc.queryForObject("SELECT count(*) FROM risk_analysis WHERE property_id = ?", Integer.class,
                propertyId);
    }

    private long insertProperty(long marketPrice) {
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude)
                VALUES ('서울특별시 위험시험구 시험로 1', '위험시험구', '김임대', ?, ?, ?, 230000000, 0, ?,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), marketPrice, new BigDecimal("37.5"),
                new BigDecimal("126.8"));
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }
}
