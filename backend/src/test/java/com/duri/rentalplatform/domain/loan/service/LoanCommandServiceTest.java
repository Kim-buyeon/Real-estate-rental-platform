package com.duri.rentalplatform.domain.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.dto.response.LoanLimitResponse;
import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.repository.LoanRegulationRepository;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.service.UserQueryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link LoanCommandService} — 404 · 422 두 종 · 기준값을 계산기로 옮기는 것. 계산 경계는 계산기 테스트가 본다. */
class LoanCommandServiceTest {

    private static final long USER_ID = 42L;
    private static final long PROPERTY_ID = 1024L;

    private UserQueryService userQueryService;
    private RiskAnalysisCommandService riskAnalysisCommandService;
    private PropertyRepository propertyRepository;
    private LoanRegulationRepository loanRegulationRepository;
    private LoanProductRepository loanProductRepository;
    private LoanCommandService service;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        propertyRepository = mock(PropertyRepository.class);
        loanRegulationRepository = mock(LoanRegulationRepository.class);
        loanProductRepository = mock(LoanProductRepository.class);
        service = new LoanCommandService(userQueryService, riskAnalysisCommandService, propertyRepository,
                loanRegulationRepository, loanProductRepository);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND, 분석하지 않는다")
    void propertyNotFound() {
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

        assertErrorCode(ErrorCode.PROPERTY_NOT_FOUND);
        verifyNoInteractions(riskAnalysisCommandService);
    }

    @Test
    @DisplayName("LOAN-01-07 주택 보유자인데 연소득 0 이면 PROFILE_INCOMPLETE, field annualIncome, 분석하지 않는다")
    void oneHouseWithoutIncome() {
        givenProperty(200_000_000L);
        givenProfile(0L, 0L, true);

        assertThatThrownBy(() -> service.calculateLimit(USER_ID, PROPERTY_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_INCOMPLETE);
                    assertThat(e.getField()).isEqualTo("annualIncome");
                });
        verifyNoInteractions(riskAnalysisCommandService, loanRegulationRepository, loanProductRepository);
    }

    @Test
    @DisplayName("보증보험 가입 불가 매물이면 LOAN_PROPERTY_NOT_ELIGIBLE")
    void notEligibleProperty() {
        givenProperty(200_000_000L);
        givenProfile(50_000_000L, 0L, false);
        givenEligible(false);

        assertErrorCode(ErrorCode.LOAN_PROPERTY_NOT_ELIGIBLE);
        verifyNoInteractions(loanRegulationRepository, loanProductRepository);
    }

    @Test
    @DisplayName("LOAN-01-05 입력: 기준값 · 금리 · 보증금 · 자격 정보를 계산기로 옮긴다")
    void calculatesFromCriteria() {
        givenProperty(200_000_000L);
        givenProfile(30_000_000L, 8_000_000L, true);
        givenEligible(true);
        givenCriteria();

        LoanLimitResponse response = service.calculateLimit(USER_ID, PROPERTY_ID);

        assertThat(response).isEqualTo(new LoanLimitResponse(160_000_000L, 180_000_000L, 95_238_095L,
                55_555_555L, 95_238_095L, AppliedRegulation.DSR, new BigDecimal("40.0"), List.of()));
    }

    @Test
    @DisplayName("LOAN-01-08 무주택 · 연소득 0 은 거르지 않고 계산한다")
    void noHouseWithoutIncomeCalculates() {
        givenProperty(200_000_000L);
        givenProfile(0L, 0L, false);
        givenEligible(true);
        givenCriteria();

        LoanLimitResponse response = service.calculateLimit(USER_ID, PROPERTY_ID);

        assertThat(response.finalLimit()).isEqualTo(160_000_000L);
        assertThat(response.appliedRegulation()).isEqualTo(AppliedRegulation.DEPOSIT_RATIO);
        assertThat(response.dtiReference()).isNull();
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.calculateLimit(USER_ID, PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenProperty(long deposit) {
        Property property = mock(Property.class);
        when(property.getDeposit()).thenReturn(deposit);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
    }

    private void givenProfile(long annualIncome, long existingLoanAnnualPayment, boolean hasHouse) {
        when(userQueryService.getProfile(USER_ID)).thenReturn(new ProfileResponse(null,
                new ProfileResponse.Profile(annualIncome, 820, 0L, existingLoanAnnualPayment, hasHouse, 0L),
                List.of()));
    }

    private void givenEligible(boolean eligible) {
        RiskResponse risk = mock(RiskResponse.class);
        when(risk.insuranceEligible()).thenReturn(eligible);
        when(riskAnalysisCommandService.analyze(PROPERTY_ID)).thenReturn(risk);
    }

    private void givenCriteria() {
        LoanRegulation regulation = mock(LoanRegulation.class);
        when(regulation.getDepositRatioLimit()).thenReturn(new BigDecimal("80.00"));
        when(regulation.getGuaranteeCapNoHouse()).thenReturn(400_000_000L);
        when(regulation.getGuaranteeCapOneHouse()).thenReturn(180_000_000L);
        when(regulation.getDsrLimit()).thenReturn(new BigDecimal("40.00"));
        when(regulation.getStressDsrRate()).thenReturn(new BigDecimal("3.00"));
        when(loanRegulationRepository.findFirstByOrderByEffectiveDateDescRegulationIdDesc())
                .thenReturn(Optional.of(regulation));

        LoanProduct product = mock(LoanProduct.class);
        when(product.getInterestRate()).thenReturn(new BigDecimal("4.200"));
        when(product.getMaxLimit()).thenReturn(400_000_000L);
        when(loanProductRepository.findFirstByOrderByLoanIdAsc()).thenReturn(Optional.of(product));
    }
}
