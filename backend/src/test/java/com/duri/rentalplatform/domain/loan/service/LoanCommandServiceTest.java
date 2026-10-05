package com.duri.rentalplatform.domain.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.cache.LoanCriteriaCache;
import com.duri.rentalplatform.domain.loan.dto.response.LoanLimitResponse;
import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.vo.LoanCriteria;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.service.UserQueryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoanCommandService} — 404 · 422 두 종 · 기준값을 계산기로 옮기는 것 · 가입 여부를 위험도 조회로 얻고 매물을 한 번만 읽는 것.
 * 계산 경계는 계산기 테스트가, 대표 상품의 선택 규칙은 {@code LoanCriteriaCacheTest} 와
 * {@code LoanProductRefreshIntegrationTest} 가 본다.
 */
class LoanCommandServiceTest {

    private static final long USER_ID = 42L;
    private static final long PROPERTY_ID = 1024L;

    private UserQueryService userQueryService;
    private RiskAnalysisCommandService riskAnalysisCommandService;
    private PropertyRepository propertyRepository;
    private LoanCriteriaCache loanCriteriaCache;
    private Property property;
    private LoanCommandService service;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        riskAnalysisCommandService = mock(RiskAnalysisCommandService.class);
        propertyRepository = mock(PropertyRepository.class);
        loanCriteriaCache = mock(LoanCriteriaCache.class);
        service = new LoanCommandService(userQueryService, riskAnalysisCommandService, propertyRepository,
                loanCriteriaCache);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND, 위험도 조회도 기준표 읽기도 하지 않는다")
    void propertyNotFound() {
        when(propertyRepository.findWithPropertyTypeCodeByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());

        assertErrorCode(ErrorCode.PROPERTY_NOT_FOUND);
        verifyNoInteractions(riskAnalysisCommandService, loanCriteriaCache);
    }

    @Test
    @DisplayName("LOAN-01-07 주택 보유자인데 연소득 0 이면 PROFILE_INCOMPLETE, field annualIncome, 위험도 조회를 하지 않는다")
    void oneHouseWithoutIncome() {
        givenProperty(200_000_000L);
        givenProfile(0L, 0L, true);

        assertThatThrownBy(() -> service.calculateLimit(USER_ID, PROPERTY_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_INCOMPLETE);
                    assertThat(e.getField()).isEqualTo("annualIncome");
                });
        verifyNoInteractions(riskAnalysisCommandService, loanCriteriaCache);
    }

    @Test
    @DisplayName("보증보험 가입 불가 매물이면 LOAN_PROPERTY_NOT_ELIGIBLE, 기준표를 읽지 않는다")
    void notEligibleProperty() {
        givenProperty(200_000_000L);
        givenProfile(50_000_000L, 0L, false);
        givenEligible(false);

        assertErrorCode(ErrorCode.LOAN_PROPERTY_NOT_ELIGIBLE);
        verifyNoInteractions(loanCriteriaCache);
    }

    @Test
    @DisplayName("LOAN-01-05 입력: 기준값 · 금리 · 보증금 · 자격 정보를 계산기로 옮긴다")
    void calculatesFromCriteria() {
        givenProperty(200_000_000L);
        givenProfile(30_000_000L, 8_000_000L, true);
        givenEligible(true);
        givenCriteria(PropertyType.APARTMENT, criteria("4.200", 400_000_000L));

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
        givenCriteria(PropertyType.APARTMENT, criteria("4.200", 400_000_000L));

        LoanLimitResponse response = service.calculateLimit(USER_ID, PROPERTY_ID);

        assertThat(response.finalLimit()).isEqualTo(160_000_000L);
        assertThat(response.appliedRegulation()).isEqualTo(AppliedRegulation.DEPOSIT_RATIO);
        assertThat(response.dtiReference()).isNull();
    }

    @Test
    @DisplayName("기준값은 매물 유형의 것을 쓴다 — 오피스텔 매물은 아파트의 금리가 아니라 오피스텔의 금리로 계산한다")
    void usesCriteriaOfPropertyType() {
        givenProperty(200_000_000L, PropertyType.OFFICETEL);
        givenProfile(30_000_000L, 8_000_000L, true);
        givenEligible(true);
        givenCriteria(Map.of(
                PropertyType.APARTMENT, criteria("4.200", 400_000_000L),
                PropertyType.OFFICETEL, criteria("5.000", 400_000_000L)));

        LoanLimitResponse response = service.calculateLimit(USER_ID, PROPERTY_ID);

        // 여력 = 3천만 × 40% − 800만 = 400만. DSR 한도 = 400만 ÷ 5% = 8천만 — 4.2% 면 95,238,095 다.
        assertThat(response.dsrLimit()).isEqualTo(80_000_000L);
    }

    @Test
    @DisplayName("그 유형의 대출 기준값이 캐시에 없으면 시드 결함이라 INTERNAL_ERROR")
    void noCriteriaForTypeIsInternalError() {
        givenProperty(200_000_000L, PropertyType.OFFICETEL);
        givenProfile(30_000_000L, 8_000_000L, true);
        givenEligible(true);
        givenCriteria(PropertyType.APARTMENT, criteria("4.200", 400_000_000L));

        assertErrorCode(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("가입 여부는 위험도 분석을 새로 부르지 않고 이미 읽은 매물로 조회 메서드를 부른다 — 매물은 한 번만 읽는다")
    void usesLatestOrAnalyzeWithAlreadyReadProperty() {
        givenProperty(200_000_000L);
        givenProfile(30_000_000L, 8_000_000L, true);
        givenEligible(true);
        givenCriteria(PropertyType.APARTMENT, criteria("4.200", 400_000_000L));

        service.calculateLimit(USER_ID, PROPERTY_ID);

        verify(riskAnalysisCommandService, never()).analyze(any(Long.class));
        verify(riskAnalysisCommandService, never()).findLatestOrAnalyze(any(Long.class));
        verify(riskAnalysisCommandService, times(1)).findLatestOrAnalyze(property);
        verify(propertyRepository, times(1)).findWithPropertyTypeCodeByPropertyId(PROPERTY_ID);
        verify(propertyRepository, never()).findById(any());
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.calculateLimit(USER_ID, PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void givenProperty(long deposit) {
        givenProperty(deposit, PropertyType.APARTMENT);
    }

    private void givenProperty(long deposit, PropertyType propertyType) {
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn(propertyType.name());
        property = mock(Property.class);
        when(property.getDeposit()).thenReturn(deposit);
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(propertyRepository.findWithPropertyTypeCodeByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(property));
    }

    private void givenProfile(long annualIncome, long existingLoanAnnualPayment, boolean hasHouse) {
        when(userQueryService.getProfile(USER_ID)).thenReturn(new ProfileResponse(null,
                new ProfileResponse.Profile(annualIncome, 820, 0L, existingLoanAnnualPayment, hasHouse, 0L),
                List.of()));
    }

    private void givenEligible(boolean eligible) {
        RiskResponse risk = mock(RiskResponse.class);
        when(risk.insuranceEligible()).thenReturn(eligible);
        when(riskAnalysisCommandService.findLatestOrAnalyze(property)).thenReturn(risk);
    }

    private void givenCriteria(PropertyType type, LoanLimitCriteria criteria) {
        givenCriteria(Map.of(type, criteria));
    }

    private void givenCriteria(Map<PropertyType, LoanLimitCriteria> loanLimitCriteria) {
        when(loanCriteriaCache.current()).thenReturn(new LoanCriteria(loanLimitCriteria));
    }

    /** 시드와 같은 규제 값(보증금 80% · 무주택 상한 4억 · 보유 상한 1.8억 · DSR 40% · 스트레스 3%p)에 금리 · 상품 한도를 얹는다. */
    private static LoanLimitCriteria criteria(String interestRate, long productMaxLimit) {
        return new LoanLimitCriteria(new BigDecimal("80.00"), 400_000_000L, 180_000_000L, new BigDecimal("40.00"),
                new BigDecimal("3.00"), new BigDecimal(interestRate), productMaxLimit);
    }
}
