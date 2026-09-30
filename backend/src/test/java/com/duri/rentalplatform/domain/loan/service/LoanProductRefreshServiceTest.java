package com.duri.rentalplatform.domain.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanProductRefreshReport;
import com.duri.rentalplatform.domain.loan.vo.LoanProductWriteResult;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.external.loanrate.BankLoanRate;
import com.duri.rentalplatform.external.loanrate.JeonseLoanRateClient;
import com.duri.rentalplatform.external.loanrate.LoanRateHouseType;
import com.duri.rentalplatform.external.loanrate.LoanRateQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link LoanProductRefreshService} — 매물 유형마다 받아 쓰고, 실패 · 실적 없음은 그 유형만 건너뛴다. */
class LoanProductRefreshServiceTest {

    private static final YearMonth BASE_MONTH = YearMonth.of(2026, 8);
    private static final long MAX_LIMIT = 400_000_000L;
    private static final List<BankLoanRate> RATES =
            List.of(new BankLoanRate("가은행", new BigDecimal("4.10"), 1_000L));

    private JeonseLoanRateClient client;
    private LoanProductRefreshWriter writer;
    private LoanProductRepository repository;
    private LoanProductRefreshService service;

    @BeforeEach
    void setUp() {
        client = mock(JeonseLoanRateClient.class);
        writer = mock(LoanProductRefreshWriter.class);
        repository = mock(LoanProductRepository.class);
        service = new LoanProductRefreshService(client, writer, repository, MAX_LIMIT);
        when(writer.write(any(), any(), any(), anyLong())).thenReturn(new LoanProductWriteResult(1, 0, 0));
    }

    @Test
    @DisplayName("아파트 · 오피스텔을 각자의 주택 유형 코드로 조회하고, 설정한 상품 한도로 쓴다")
    void refreshesEachHouseType() {
        givenRates(LoanRateHouseType.APARTMENT, RATES);
        givenRates(LoanRateHouseType.OFFICETEL, RATES);

        LoanProductRefreshReport report = service.refresh(BASE_MONTH);

        assertThat(report.refreshed()).containsExactly(PropertyType.APARTMENT, PropertyType.OFFICETEL);
        verify(writer).write(PropertyType.APARTMENT, BASE_MONTH, RATES, MAX_LIMIT);
        verify(writer).write(PropertyType.OFFICETEL, BASE_MONTH, RATES, MAX_LIMIT);
    }

    @Test
    @DisplayName("한 유형의 연동 실패는 그 유형만 실패로 남기고 쓰지 않는다 — 다른 유형은 반영한다")
    void failureSkipsOnlyThatType() {
        when(client.findBankLoanRates(new LoanRateQuery(BASE_MONTH, LoanRateHouseType.APARTMENT)))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));
        givenRates(LoanRateHouseType.OFFICETEL, RATES);

        LoanProductRefreshReport report = service.refresh(BASE_MONTH);

        assertThat(report.failed()).containsExactly(PropertyType.APARTMENT);
        assertThat(report.refreshed()).containsExactly(PropertyType.OFFICETEL);
        verify(writer, never()).write(eq(PropertyType.APARTMENT), any(), any(), anyLong());
    }

    @Test
    @DisplayName("실적 없음(빈 목록)은 쓰지 않는다 — 기존 행을 지운 채로 두지 않는다")
    void emptyResultKeepsExistingRows() {
        givenRates(LoanRateHouseType.APARTMENT, List.of());
        givenRates(LoanRateHouseType.OFFICETEL, List.of());

        LoanProductRefreshReport report = service.refresh(BASE_MONTH);

        assertThat(report.empty()).containsExactly(PropertyType.APARTMENT, PropertyType.OFFICETEL);
        verify(writer, never()).write(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("연동 실패가 아닌 예외는 삼키지 않는다")
    void otherFailurePropagates() {
        when(client.findBankLoanRates(any())).thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR));

        assertThatThrownBy(() -> service.refresh(BASE_MONTH)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("기준월 행이 없는 유형이 하나라도 있으면 stale, 둘 다 있으면 아니다")
    void staleWhenAnyHouseTypeMissesBaseMonth() {
        LocalDate first = BASE_MONTH.atDay(1);
        when(repository.existsByHouseTypeAndBaseMonthGreaterThanEqual(PropertyType.APARTMENT, first)).thenReturn(true);
        when(repository.existsByHouseTypeAndBaseMonthGreaterThanEqual(PropertyType.OFFICETEL, first)).thenReturn(false);
        assertThat(service.isStale(BASE_MONTH)).isTrue();

        when(repository.existsByHouseTypeAndBaseMonthGreaterThanEqual(PropertyType.OFFICETEL, first)).thenReturn(true);
        assertThat(service.isStale(BASE_MONTH)).isFalse();
    }

    private void givenRates(LoanRateHouseType houseType, List<BankLoanRate> rates) {
        when(client.findBankLoanRates(new LoanRateQuery(BASE_MONTH, houseType))).thenReturn(rates);
    }
}
