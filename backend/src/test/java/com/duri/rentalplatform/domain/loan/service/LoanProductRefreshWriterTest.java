package com.duri.rentalplatform.domain.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanProductWriteResult;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import com.duri.rentalplatform.external.loanrate.BankLoanRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoanProductRefreshWriter} 의 기준표 캐시 무효화 — 행을 넣거나 고쳤을 때만 건다. 행 자체의 저장 규칙(자연키 갱신 · 기준월 역행
 * 방지)은 {@code LoanProductRefreshIntegrationTest} 가 실제 DB 로 본다.
 */
class LoanProductRefreshWriterTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final long MAX_LIMIT = 400_000_000L;

    private LoanProductRepository repository;
    private JudgementCriteriaCache criteriaCache;
    private LoanProductRefreshWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(LoanProductRepository.class);
        criteriaCache = mock(JudgementCriteriaCache.class);
        writer = new LoanProductRefreshWriter(repository, criteriaCache);
    }

    @Test
    @DisplayName("새 은행 행을 넣으면 기준표 캐시 무효화를 건다")
    void insertInvalidatesCache() {
        when(repository.findAllByHouseType(PropertyType.APARTMENT)).thenReturn(List.of());

        LoanProductWriteResult result = writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(new BankLoanRate("가은행", new BigDecimal("4.00"), 100L)), MAX_LIMIT);

        assertThat(result).isEqualTo(new LoanProductWriteResult(1, 0, 0));
        verify(criteriaCache, times(1)).invalidateAfterCommit();
    }

    @Test
    @DisplayName("지난 기준월 행을 새 기준월로 고치면 기준표 캐시 무효화를 건다")
    void updateInvalidatesCache() {
        LoanProduct existing = existing("가은행", LocalDate.of(2026, 7, 1));
        when(repository.findAllByHouseType(PropertyType.APARTMENT)).thenReturn(List.of(existing));

        LoanProductWriteResult result = writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(new BankLoanRate("가은행", new BigDecimal("3.90"), 200L)), MAX_LIMIT);

        assertThat(result).isEqualTo(new LoanProductWriteResult(0, 1, 0));
        verify(criteriaCache, times(1)).invalidateAfterCommit();
    }

    @Test
    @DisplayName("바뀐 행이 없으면(같은 기준월 이후) 기준표 캐시를 무효화하지 않는다")
    void unchangedDoesNotInvalidateCache() {
        LoanProduct existing = existing("가은행", AUGUST.atDay(1));
        when(repository.findAllByHouseType(PropertyType.APARTMENT)).thenReturn(List.of(existing));

        LoanProductWriteResult result = writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(new BankLoanRate("가은행", new BigDecimal("3.90"), 200L)), MAX_LIMIT);

        assertThat(result).isEqualTo(new LoanProductWriteResult(0, 0, 1));
        verify(criteriaCache, never()).invalidateAfterCommit();
    }

    private static LoanProduct existing(String bankName, LocalDate baseMonth) {
        LoanProduct product = mock(LoanProduct.class);
        when(product.getBankName()).thenReturn(bankName);
        when(product.getBaseMonth()).thenReturn(baseMonth);
        return product;
    }
}
