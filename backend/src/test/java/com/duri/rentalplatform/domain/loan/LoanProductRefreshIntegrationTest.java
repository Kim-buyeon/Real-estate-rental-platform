package com.duri.rentalplatform.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.service.LoanProductRefreshService;
import com.duri.rentalplatform.domain.loan.service.LoanProductRefreshWriter;
import com.duri.rentalplatform.domain.loan.vo.LoanProductRefreshReport;
import com.duri.rentalplatform.domain.loan.vo.LoanProductWriteResult;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.external.loanrate.BankLoanRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대출 상품 금리 갱신 — Mock 연동 → 서비스 → 저장, 자연키 갱신, 대표 상품 선택 규칙을 실제 PostgreSQL(V16 스키마)에서 본다.
 *
 * <p>공유 컨테이너다. 다른 테스트(스키마 검증)가 시드 1행을 센다 — 넣은 API 행(주택 유형 있음)은 매 테스트 뒤 지운다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LoanProductRefreshIntegrationTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final long MAX_LIMIT = 400_000_000L;

    @Autowired
    LoanProductRefreshService refreshService;

    @Autowired
    LoanProductRefreshWriter writer;

    @Autowired
    LoanProductRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeApiRows() {
        jdbcTemplate.update("DELETE FROM loan_product WHERE house_type IS NOT NULL");
    }

    @Test
    @DisplayName("Mock 연동으로 두 매물 유형 × 3개 은행을 넣고, 설정한 상품 한도 · 기준월 · 감사 시각이 채워진다")
    void refreshInsertsMockRates() {
        LoanProductRefreshReport report = refreshService.refresh(AUGUST);

        assertThat(report.refreshed()).containsExactly(PropertyType.APARTMENT, PropertyType.OFFICETEL);
        assertThat(report.inserted()).isEqualTo(6);
        List<LoanProduct> apartments = repository.findAllByHouseType(PropertyType.APARTMENT);
        assertThat(apartments).hasSize(3).allSatisfy(product -> {
            assertThat(product.getBaseMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(product.getMaxLimit()).isEqualTo(MAX_LIMIT);
            assertThat(product.getRateType()).isNull();
            assertThat(product.getLoanTerm()).isNull();
            assertThat(product.getRepaymentType()).isEqualTo("BULLET");
            assertThat(product.isHouseOwnershipCondition()).isTrue();
            assertThat(product.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("같은 은행 · 유형은 새 기준월이면 고치고, 지난 기준월이면 두며, 처음 보는 은행은 넣는다")
    void writeUpsertsByNaturalKey() {
        writer.write(PropertyType.APARTMENT, JULY, List.of(rate("가은행", "4.00", 100L)), MAX_LIMIT);

        LoanProductWriteResult august = writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(rate("가은행", "3.90", 200L), rate("나은행", "4.20", 50L)), MAX_LIMIT);
        LoanProductWriteResult rerunJuly = writer.write(PropertyType.APARTMENT, JULY,
                List.of(rate("가은행", "9.99", 1L)), MAX_LIMIT);

        assertThat(august).isEqualTo(new LoanProductWriteResult(1, 1, 0));
        assertThat(rerunJuly).isEqualTo(new LoanProductWriteResult(0, 0, 1));
        LoanProduct ga = repository.findAllByHouseType(PropertyType.APARTMENT).stream()
                .filter(product -> product.getBankName().equals("가은행")).findFirst().orElseThrow();
        assertThat(ga.getInterestRate()).isEqualByComparingTo("3.90");
        assertThat(ga.getBaseMonth()).isEqualTo(AUGUST.atDay(1));
    }

    @Test
    @DisplayName("대표 상품: 최신 기준월에서 실행금액 최대 — 지난달 금액이 더 큰 은행 · 금리가 더 낮은 은행보다 앞선다")
    void representativeIsLargestAmountInLatestMonth() {
        writer.write(PropertyType.APARTMENT, JULY, List.of(rate("지난달은행", "3.00", 999_999L)), MAX_LIMIT);
        writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(rate("저금리은행", "1.50", 10L), rate("최대은행", "4.40", 500L)), MAX_LIMIT);

        assertThat(representative(PropertyType.APARTMENT)).isEqualTo("최대은행");
    }

    @Test
    @DisplayName("대표 상품: 실행금액이 같으면 금리가 낮은 은행")
    void representativeTieBreaksByLowerRate() {
        writer.write(PropertyType.APARTMENT, AUGUST,
                List.of(rate("높은은행", "4.40", 500L), rate("낮은은행", "4.10", 500L)), MAX_LIMIT);

        assertThat(representative(PropertyType.APARTMENT)).isEqualTo("낮은은행");
    }

    @Test
    @DisplayName("대표 상품: 매물 유형이 다르면 고르지 않고, API 행이 없으면 시드 예시 행이 남아 있다")
    void representativeIsPerHouseTypeWithSeedFallback() {
        writer.write(PropertyType.APARTMENT, AUGUST, List.of(rate("가은행", "4.00", 100L)), MAX_LIMIT);

        assertThat(repository.findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(
                PropertyType.OFFICETEL)).isEmpty();
        assertThat(repository.findFirstByHouseTypeIsNullOrderByLoanIdAsc())
                .get().extracting(LoanProduct::getInterestRate).satisfies(
                        rate -> assertThat(rate).isEqualByComparingTo("4.200"));
    }

    private String representative(PropertyType houseType) {
        return repository.findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(houseType)
                .orElseThrow().getBankName();
    }

    private static BankLoanRate rate(String bankName, String rate, long loanAmount) {
        return new BankLoanRate(bankName, new BigDecimal(rate), loanAmount);
    }
}
