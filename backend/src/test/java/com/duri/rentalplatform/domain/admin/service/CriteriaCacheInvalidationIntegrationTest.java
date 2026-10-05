package com.duri.rentalplatform.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.admin.dto.request.LoanRegulationUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.RiskThresholdUpdateRequest;
import com.duri.rentalplatform.domain.loan.cache.LoanCriteriaCache;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 관리자 기준 수정이 건 커밋 뒤 무효화가 올바른 기준표 캐시만 비우는지를 실제 PostgreSQL · Redis 로 본다(#399).
 *
 * <p>두 캐시를 스파이로 두고 {@code invalidateAfterCommit} 호출을 센다. 캐시 인스턴스가 바뀌었는지(동일성)로 보지 않는다 — 기준 변경이
 * Redis 버전 키를 올리면 같은 프로세스의 반복 작업이 <b>두 캐시를 모두</b> 다시 읽으므로(정상 동작) 동일성은 경합한다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 무효화는 기준을 바꾼 트랜잭션이 커밋된 뒤에 걸린다. 바꾼 기준은 끝에 시드 값으로
 * 되돌린다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CriteriaCacheInvalidationIntegrationTest {

    private static final BigDecimal SEED_NEGATIVE_EQUITY = new BigDecimal("80.00");
    private static final BigDecimal SEED_CAUTION = new BigDecimal("70.00");

    @Autowired
    CriteriaCommandService criteriaCommandService;

    @MockitoSpyBean
    JudgementCriteriaCache judgementCriteriaCache;

    @MockitoSpyBean
    LoanCriteriaCache loanCriteriaCache;

    @Autowired
    JdbcTemplate jdbc;

    private long adminId;
    private Map<String, Object> originalRegulation;

    @BeforeEach
    void insertAdmin() {
        adminId = jdbc.queryForObject("""
                INSERT INTO users (name, email, role, credit_score)
                VALUES ('기준무효화시험관리자', ?, 'ADMIN', 800)
                RETURNING user_id
                """, Long.class, "criteria-invalidation-" + UUID.randomUUID() + "@example.com");
        originalRegulation = jdbc.queryForMap("""
                SELECT regulation_id, deposit_ratio_limit, guarantee_cap_no_house, guarantee_cap_one_house,
                       dsr_limit, stress_dsr_rate, dti_limit
                FROM loan_regulation ORDER BY regulation_id LIMIT 1
                """);
    }

    @AfterEach
    void restore() {
        criteriaCommandService.updateRiskThreshold(adminId, new RiskThresholdUpdateRequest(
                SEED_NEGATIVE_EQUITY, SEED_CAUTION, "기준무효화시험 복구"));
        criteriaCommandService.updateLoanRegulations(adminId, regulationRequest(
                (BigDecimal) originalRegulation.get("deposit_ratio_limit")));
        jdbc.update("DELETE FROM criteria_change_history WHERE changed_by = ?", adminId);
        jdbc.update("DELETE FROM users WHERE user_id = ?", adminId);
    }

    @Test
    @DisplayName("관리자 대출 규제 수정은 대출 기준 캐시만 무효화하고 위험도 기준 캐시는 건드리지 않는다 — 새 값이 대출 캐시에 보인다")
    void loanRegulationChangeInvalidatesOnlyLoanCache() {
        BigDecimal original = (BigDecimal) originalRegulation.get("deposit_ratio_limit");
        BigDecimal changed = original.subtract(BigDecimal.ONE);

        criteriaCommandService.updateLoanRegulations(adminId, regulationRequest(changed));

        verify(loanCriteriaCache, times(1)).invalidateAfterCommit();
        verify(judgementCriteriaCache, never()).invalidateAfterCommit();
        assertThat(loanCriteriaCache.current().findLoanLimitCriteria(PropertyType.APARTMENT).orElseThrow()
                .depositRatioLimit()).isEqualByComparingTo(changed);
    }

    @Test
    @DisplayName("관리자 위험 등급 기준 수정은 위험도 기준 캐시만 무효화하고 대출 기준 캐시는 건드리지 않는다")
    void riskThresholdChangeInvalidatesOnlyJudgementCache() {
        criteriaCommandService.updateRiskThreshold(adminId, new RiskThresholdUpdateRequest(
                new BigDecimal("75.00"), SEED_CAUTION, "기준무효화시험"));

        verify(judgementCriteriaCache, times(1)).invalidateAfterCommit();
        verify(loanCriteriaCache, never()).invalidateAfterCommit();
    }

    private LoanRegulationUpdateRequest regulationRequest(BigDecimal depositRatioLimit) {
        return new LoanRegulationUpdateRequest(List.of(new LoanRegulationUpdateRequest.Regulation(
                ((Number) originalRegulation.get("regulation_id")).longValue(),
                depositRatioLimit,
                ((Number) originalRegulation.get("guarantee_cap_no_house")).longValue(),
                ((Number) originalRegulation.get("guarantee_cap_one_house")).longValue(),
                (BigDecimal) originalRegulation.get("dsr_limit"),
                (BigDecimal) originalRegulation.get("stress_dsr_rate"),
                (BigDecimal) originalRegulation.get("dti_limit"))), "기준무효화시험");
    }
}
