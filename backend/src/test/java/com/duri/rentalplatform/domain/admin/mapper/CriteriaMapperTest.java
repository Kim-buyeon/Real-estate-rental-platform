package com.duri.rentalplatform.domain.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.admin.dto.condition.CriteriaHistoryCondition;
import com.duri.rentalplatform.domain.admin.dto.response.CriteriaHistoryResponse;
import com.duri.rentalplatform.domain.admin.dto.response.GuaranteeCriteriaResponse;
import com.duri.rentalplatform.domain.admin.dto.response.PremiumRatesResponse;
import com.duri.rentalplatform.domain.admin.dto.response.RiskThresholdResponse;
import com.duri.rentalplatform.domain.admin.enums.CriteriaTarget;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
 * {@link CriteriaMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>기준 테이블은 시드(V7)가 이미 채우므로 테스트마다 비우고 픽스처를 넣는다. 테스트마다 롤백한다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class CriteriaMapperTest {

    private static final OffsetDateTime SEOUL_2026_07_01 =
            OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    CriteriaMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private long adminId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM criteria_change_history");
        jdbc.update("UPDATE risk_analysis SET eligible_guarantee_id = NULL");
        jdbc.update("DELETE FROM guarantee_premium_rate");
        jdbc.update("DELETE FROM insurance_product");
        jdbc.update("DELETE FROM hug_criteria");
        jdbc.update("DELETE FROM hf_criteria");
        jdbc.update("DELETE FROM sgi_criteria");
        jdbc.update("DELETE FROM guarantee_criteria");
        adminId = insertUser("admin@example.com", false);
    }

    @Test
    @DisplayName("기관 기준: 모든 필드가 채워지고 HF 만 연계 여부를 갖는다. 시각은 서울 오프셋, HF 는 늦은 수정일시")
    void selectGuaranteeCriteria() {
        long hug = insertGuarantee("HUG", "60.00", "2026-07-01 00:00:00");
        long hf = insertGuarantee("HF", null, "2026-07-01 00:00:00");
        jdbc.update("""
                INSERT INTO hf_criteria (guarantee_id, loan_linked_required_yn, lowest_premium_yn, youth_discount_yn,
                                         newlywed_discount_yn, updated_at)
                VALUES (?, TRUE, FALSE, FALSE, FALSE, TIMESTAMP '2026-07-02 00:00:00')
                """, hf);

        List<GuaranteeCriteriaResponse.Provider> rows = mapper.selectGuaranteeCriteria();

        assertThat(rows).hasSize(2);
        GuaranteeCriteriaResponse.Provider first = rows.get(0);
        assertThat(first.provider()).isEqualTo(GuaranteeProvider.HUG);
        assertThat(first.collateralRatio()).isEqualByComparingTo("90.00");
        assertThat(first.maxDeposit()).isEqualTo(700_000_000L);
        assertThat(first.seniorDebtRatioLimit()).isEqualByComparingTo("60.00");
        assertThat(first.requiresLoanLink()).isFalse();
        assertThat(first.updatedAt()).isEqualTo(SEOUL_2026_07_01);
        assertThat(rows.get(1).requiresLoanLink()).isTrue();
        assertThat(rows.get(1).seniorDebtRatioLimit()).isNull();
        assertThat(rows.get(1).updatedAt()).isEqualTo(SEOUL_2026_07_01.plusDays(1));
        assertThat(hug).isLessThan(hf);
    }

    @Test
    @DisplayName("보증료율: 모든 필드가 채워지고 보증금 상한 NULL 은 null")
    void selectPremiumRates() {
        long hug = insertGuarantee("HUG", null, "2026-07-01 00:00:00");
        jdbc.update("""
                INSERT INTO guarantee_premium_rate (guarantee_id, house_type, deposit_min, deposit_max, debt_ratio_min,
                                                    debt_ratio_max, premium_rate, updated_at)
                VALUES (?, 'APARTMENT', 0, NULL, 0.00, 80.00, 0.097, TIMESTAMP '2026-07-01 00:00:00')
                """, hug);

        List<PremiumRatesResponse.Item> rows = mapper.selectPremiumRates();

        assertThat(rows).singleElement().satisfies(item -> {
            assertThat(item.premiumRateId()).isNotNull();
            assertThat(item.provider()).isEqualTo(GuaranteeProvider.HUG);
            assertThat(item.houseType()).isEqualTo(HouseType.APARTMENT);
            assertThat(item.depositMin()).isZero();
            assertThat(item.depositMax()).isNull();
            assertThat(item.debtRatioMin()).isEqualByComparingTo("0.00");
            assertThat(item.debtRatioMax()).isEqualByComparingTo("80.00");
            assertThat(item.premiumRate()).isEqualByComparingTo("0.097");
            assertThat(item.updatedAt()).isEqualTo(SEOUL_2026_07_01);
        });
    }

    @Test
    @DisplayName("위험 기준: 단일 행의 두 값과 수정일시")
    void selectRiskThreshold() {
        jdbc.update("UPDATE risk_criteria SET negative_equity_ratio = 80.00, caution_lease_ratio = 70.00, "
                + "updated_at = TIMESTAMP '2026-07-01 00:00:00'");

        RiskThresholdResponse threshold = mapper.selectRiskThreshold();

        assertThat(threshold.negativeEquityRatio()).isEqualByComparingTo("80.00");
        assertThat(threshold.cautionLeaseRatio()).isEqualByComparingTo("70.00");
        assertThat(threshold.updatedAt()).isEqualTo(SEOUL_2026_07_01);
    }

    @Test
    @DisplayName("이력: 모든 필드가 채워지고 열거 · 서울 오프셋으로 돌아온다")
    void historyMapsAllFields() {
        long id = insertHistory(adminId, "2026-07-29 11:00:00");

        List<CriteriaHistoryResponse> rows = mapper.selectHistory(new CriteriaHistoryCondition(null, null, 21));

        assertThat(rows).singleElement().isEqualTo(new CriteriaHistoryResponse(id, CriteriaTarget.GUARANTEE_CRITERIA,
                "HUG", "maxDeposit", "700000000", "500000000", "사유", "admin@example.com",
                OffsetDateTime.of(2026, 7, 29, 11, 0, 0, 0, ZoneOffset.ofHours(9))));
    }

    @Test
    @DisplayName("이력: 탈퇴한 관리자의 changedBy 는 null 이고 행은 남는다")
    void deletedAdminEmailIsNull() {
        long deleted = insertUser("gone@example.com", true);
        insertHistory(deleted, "2026-07-29 11:00:00");

        assertThat(mapper.selectHistory(new CriteriaHistoryCondition(null, null, 21)))
                .singleElement().extracting(CriteriaHistoryResponse::changedBy).isNull();
    }

    @Test
    @DisplayName("이력 커서: 시각 내림차순, 같은 시각은 식별자 내림차순으로 다음 페이지에 이어진다")
    void historyCursor() {
        long a = insertHistory(adminId, "2026-07-29 11:00:00");
        long b = insertHistory(adminId, "2026-07-29 11:00:00");
        long c = insertHistory(adminId, "2026-07-28 11:00:00");

        List<CriteriaHistoryResponse> first = mapper.selectHistory(new CriteriaHistoryCondition(null, null, 2));
        assertThat(first).extracting(CriteriaHistoryResponse::historyId).containsExactly(b, a);

        List<CriteriaHistoryResponse> second = mapper.selectHistory(
                new CriteriaHistoryCondition(LocalDateTime.of(2026, 7, 29, 11, 0), b, 2));
        assertThat(second).extracting(CriteriaHistoryResponse::historyId).containsExactly(a, c);

        List<CriteriaHistoryResponse> last = mapper.selectHistory(
                new CriteriaHistoryCondition(LocalDateTime.of(2026, 7, 28, 11, 0), c, 2));
        assertThat(last).isEmpty();
    }

    private long insertGuarantee(String provider, String seniorLimit, String updatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO guarantee_criteria (provider, max_deposit, collateral_ratio, senior_debt_ratio_limit,
                                                updated_at)
                VALUES (?, 700000000, 90.00, CAST(? AS NUMERIC), CAST(? AS TIMESTAMP))
                RETURNING guarantee_id
                """, Long.class, provider, seniorLimit, updatedAt);
    }

    private long insertHistory(long changedBy, String changedAt) {
        return jdbc.queryForObject("""
                INSERT INTO criteria_change_history (change_group_id, target_table, target_id, target_key, field_name,
                                                     before_value, after_value, change_reason, changed_by, changed_at)
                VALUES (gen_random_uuid(), 'GUARANTEE_CRITERIA', 1, 'HUG', 'maxDeposit', '700000000', '500000000',
                        '사유', ?, CAST(? AS TIMESTAMP))
                RETURNING history_id
                """, Long.class, changedBy, changedAt);
    }

    private long insertUser(String email, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO users (name, email, role, credit_score, deleted_at)
                VALUES ('관리자', ?, 'ADMIN', 800, CASE WHEN ? THEN TIMESTAMP '2026-08-01 00:00:00' END)
                RETURNING user_id
                """, Long.class, email, deleted);
    }
}
