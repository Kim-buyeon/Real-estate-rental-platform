package com.duri.rentalplatform.external.loanrate;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 전세자금대출 금리 Mock 구현. 조회 조건과 무관하게 항상 같은 은행별 금리를 돌려준다 — 네트워크 · 시각에 무관.
 *
 * <p>은행명은 제공처 문서의 샘플 표기(「가은행」)를 따라 가상 이름을 쓴다. 실제 은행명을 쓰면 로컬 DB 에 쌓인 Mock 행이 실데이터로
 * 오인된다.
 */
@Component
@ConditionalOnProperty(prefix = "external.jeonse-loan-rate", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockJeonseLoanRateClient implements JeonseLoanRateClient {

    private static final List<BankLoanRate> RATES = List.of(
            new BankLoanRate("가은행", new BigDecimal("3.85"), 50_000_000_000L),
            new BankLoanRate("나은행", new BigDecimal("4.10"), 120_000_000_000L),
            new BankLoanRate("다은행", new BigDecimal("4.45"), 8_000_000_000L));

    @Override
    public List<BankLoanRate> findBankLoanRates(LoanRateQuery query) {
        return RATES;
    }
}
