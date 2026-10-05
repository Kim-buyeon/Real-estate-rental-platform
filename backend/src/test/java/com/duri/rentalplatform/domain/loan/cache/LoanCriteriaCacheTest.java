package com.duri.rentalplatform.domain.loan.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.cache.CriteriaVersionStore;
import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.repository.LoanRegulationRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanCriteria;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link LoanCriteriaCache} 의 적재 — 대표 상품 선택 · 규제 값 옮기기 · 빈 유형. 위험도 기준 캐시 테스트에 있던 대출 기준값 사례를
 * 캐시 분리(#399)와 함께 옮겼다. 공통 동작(무효화 · 버전 키 · 겹친 적재)은 위험도 기준 캐시 테스트가 본다 — 같은 상위 클래스다.
 */
class LoanCriteriaCacheTest {

    private LoanRegulationRepository loanRegulationRepository;
    private LoanProductRepository loanProductRepository;
    private LoanCriteriaCache cache;

    @BeforeEach
    void setUp() {
        loanRegulationRepository = mock(LoanRegulationRepository.class);
        loanProductRepository = mock(LoanProductRepository.class);
        cache = new LoanCriteriaCache(loanRegulationRepository, loanProductRepository,
                new CriteriaVersionStore(mock(StringRedisTemplate.class)), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("대출 기준값은 매물 유형의 HF 금리 대표 행을 쓰고, 그 유형의 행이 없으면 시드 예시 행을 쓴다")
    void loanCriteriaUsesTypeProductThenSeedProduct() {
        givenRegulation();
        LoanProduct officetel = product("5.000", 300_000_000L);
        LoanProduct seed = product("4.200", 400_000_000L);
        when(loanProductRepository.findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(
                PropertyType.OFFICETEL)).thenReturn(Optional.of(officetel));
        when(loanProductRepository.findFirstByHouseTypeIsNullOrderByLoanIdAsc())
                .thenReturn(Optional.of(seed));

        LoanCriteria criteria = cache.current();

        assertThat(criteria.findLoanLimitCriteria(PropertyType.OFFICETEL).orElseThrow().interestRate())
                .isEqualByComparingTo("5.000");
        assertThat(criteria.findLoanLimitCriteria(PropertyType.OFFICETEL).orElseThrow().productMaxLimit())
                .isEqualTo(300_000_000L);
        assertThat(criteria.findLoanLimitCriteria(PropertyType.APARTMENT).orElseThrow().interestRate())
                .isEqualByComparingTo("4.200");
    }

    @Test
    @DisplayName("대출 기준값은 최신 규제의 값을 계산기 입력으로 옮긴다")
    void loanCriteriaCarriesRegulation() {
        givenLoanCriteria("4.200");

        var loan = cache.current().findLoanLimitCriteria(PropertyType.APARTMENT).orElseThrow();

        assertThat(loan.depositRatioLimit()).isEqualByComparingTo("80.00");
        assertThat(loan.guaranteeCapNoHouse()).isEqualTo(400_000_000L);
        assertThat(loan.guaranteeCapOneHouse()).isEqualTo(180_000_000L);
        assertThat(loan.dsrLimit()).isEqualByComparingTo("40.00");
        assertThat(loan.stressDsrRate()).isEqualByComparingTo("3.00");
        assertThat(loan.productMaxLimit()).isEqualTo(400_000_000L);
    }

    @Test
    @DisplayName("다시 읽으면 바뀐 대출 상품 금리가 보인다")
    void reloadPicksUpChangedProduct() {
        givenLoanCriteria("4.200");
        LoanCriteria before = cache.current();
        givenLoanCriteria("5.100");

        cache.reload();

        assertThat(before.findLoanLimitCriteria(PropertyType.APARTMENT).orElseThrow().interestRate())
                .isEqualByComparingTo("4.200");
        assertThat(cache.current().findLoanLimitCriteria(PropertyType.APARTMENT).orElseThrow().interestRate())
                .isEqualByComparingTo("5.100");
    }

    @Test
    @DisplayName("대표 행도 시드 행도 없는 유형은 빈다 — 한도 조회가 시드 결함으로 낸다")
    void typeWithoutAnyProductIsAbsent() {
        givenRegulation();

        assertThat(cache.current().findLoanLimitCriteria(PropertyType.APARTMENT)).isEmpty();
        assertThat(cache.current().findLoanLimitCriteria(PropertyType.OFFICETEL)).isEmpty();
    }

    @Test
    @DisplayName("대출 규제 행이 없으면 모든 유형이 빈다")
    void noRegulationLeavesLoanCriteriaEmpty() {
        LoanProduct seedRow = product("4.200", 400_000_000L);
        when(loanProductRepository.findFirstByHouseTypeIsNullOrderByLoanIdAsc()).thenReturn(Optional.of(seedRow));

        assertThat(cache.current().limitCriteriaByType()).isEmpty();
    }

    /** 규제 한 행과 시드 예시 상품(금리 인자)을 둔다. 매물 유형의 API 행은 없다. */
    private void givenLoanCriteria(String seedInterestRate) {
        givenRegulation();
        LoanProduct seedRow = product(seedInterestRate, 400_000_000L);
        when(loanProductRepository.findFirstByHouseTypeIsNullOrderByLoanIdAsc()).thenReturn(Optional.of(seedRow));
    }

    private void givenRegulation() {
        LoanRegulation regulation = mock(LoanRegulation.class);
        when(regulation.getDepositRatioLimit()).thenReturn(new BigDecimal("80.00"));
        when(regulation.getGuaranteeCapNoHouse()).thenReturn(400_000_000L);
        when(regulation.getGuaranteeCapOneHouse()).thenReturn(180_000_000L);
        when(regulation.getDsrLimit()).thenReturn(new BigDecimal("40.00"));
        when(regulation.getStressDsrRate()).thenReturn(new BigDecimal("3.00"));
        when(loanRegulationRepository.findFirstByOrderByEffectiveDateDescRegulationIdDesc())
                .thenReturn(Optional.of(regulation));
    }

    private static LoanProduct product(String interestRate, long maxLimit) {
        LoanProduct product = mock(LoanProduct.class);
        when(product.getInterestRate()).thenReturn(new BigDecimal(interestRate));
        when(product.getMaxLimit()).thenReturn(maxLimit);
        return product;
    }
}
