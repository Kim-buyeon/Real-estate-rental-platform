package com.duri.rentalplatform.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.domain.admin.dto.request.GuaranteeCriteriaUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.LoanRegulationUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.PremiumRateUpdateRequest;
import com.duri.rentalplatform.domain.admin.dto.request.RiskThresholdUpdateRequest;
import com.duri.rentalplatform.domain.admin.entity.CriteriaChangeHistory;
import com.duri.rentalplatform.domain.admin.enums.CriteriaTarget;
import com.duri.rentalplatform.domain.admin.repository.CriteriaChangeHistoryRepository;
import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import com.duri.rentalplatform.domain.loan.repository.LoanRegulationRepository;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.GuaranteePremiumRate;
import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link CriteriaCommandService} — 바뀐 필드만 이력, 한 요청은 한 그룹, 값 표기, 세 선 단조 검증.
 *
 * <p>기준 엔티티는 시드 전용이라 팩토리가 없어 리플렉션으로 만든다. 임계값은 테스트가 넣은 픽스처 값이다.
 */
class CriteriaCommandServiceTest {

    private static final long ADMIN_ID = 7L;
    private static final String REASON = "사유";

    private GuaranteeCriteriaRepository guaranteeRepository;
    private HfCriteriaRepository hfRepository;
    private GuaranteePremiumRateRepository premiumRateRepository;
    private RiskCriteriaRepository riskRepository;
    private LoanRegulationRepository loanRegulationRepository;
    private CriteriaChangeHistoryRepository historyRepository;
    private CriteriaCommandService service;

    private GuaranteeCriteria hug;
    private GuaranteeCriteria hf;
    private HfCriteria hfDetail;
    private RiskCriteria risk;

    @BeforeEach
    void setUp() {
        guaranteeRepository = mock(GuaranteeCriteriaRepository.class);
        hfRepository = mock(HfCriteriaRepository.class);
        premiumRateRepository = mock(GuaranteePremiumRateRepository.class);
        riskRepository = mock(RiskCriteriaRepository.class);
        loanRegulationRepository = mock(LoanRegulationRepository.class);
        historyRepository = mock(CriteriaChangeHistoryRepository.class);
        service = new CriteriaCommandService(guaranteeRepository, hfRepository, premiumRateRepository,
                riskRepository, loanRegulationRepository, historyRepository);

        hug = guarantee(1L, GuaranteeProvider.HUG, "90.00", 700_000_000L, "60.00");
        hf = guarantee(2L, GuaranteeProvider.HF, "90.00", 700_000_000L, null);
        hfDetail = entity(HfCriteria.class);
        ReflectionTestUtils.setField(hfDetail, "hfCriteriaId", 20L);
        ReflectionTestUtils.setField(hfDetail, "guaranteeId", 2L);
        ReflectionTestUtils.setField(hfDetail, "loanLinkedRequired", true);
        risk = entity(RiskCriteria.class);
        ReflectionTestUtils.setField(risk, "riskCriteriaId", 1L);
        ReflectionTestUtils.setField(risk, "negativeEquityRatio", new BigDecimal("80.00"));
        ReflectionTestUtils.setField(risk, "cautionLeaseRatio", new BigDecimal("70.00"));

        when(guaranteeRepository.findByProvider(GuaranteeProvider.HUG)).thenReturn(Optional.of(hug));
        when(guaranteeRepository.findByProvider(GuaranteeProvider.HF)).thenReturn(Optional.of(hf));
        when(guaranteeRepository.findAll()).thenReturn(List.of(hug, hf));
        when(hfRepository.findByGuaranteeId(2L)).thenReturn(Optional.of(hfDetail));
        when(riskRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenReturn(Optional.of(risk));
    }

    @Test
    @DisplayName("기관 기준: 바뀐 필드(maxDeposit)만 이력 한 행, 금액은 정수 문자열, 같은 값 90.0 은 이력 없음")
    void guaranteeRecordsOnlyChangedField() {
        service.updateGuarantee(ADMIN_ID, GuaranteeProvider.HUG, new GuaranteeCriteriaUpdateRequest(
                new BigDecimal("90.0"), 500_000_000L, null, false, REASON));

        List<CriteriaChangeHistory> rows = savedRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getTarget()).isEqualTo(CriteriaTarget.GUARANTEE_CRITERIA);
            assertThat(row.getTargetId()).isEqualTo(1L);
            assertThat(row.getTargetKey()).isEqualTo("HUG");
            assertThat(row.getFieldName()).isEqualTo("maxDeposit");
            assertThat(row.getBeforeValue()).isEqualTo("700000000");
            assertThat(row.getAfterValue()).isEqualTo("500000000");
            assertThat(row.getChangeReason()).isEqualTo(REASON);
            assertThat(row.getChangedBy()).isEqualTo(ADMIN_ID);
        });
        assertThat(hug.getMaxDeposit()).isEqualTo(500_000_000L);
        assertThat(hug.getSeniorDebtRatioLimit()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("HF: 기관 기준과 연계 여부가 함께 바뀌면 두 행이 같은 그룹 ID, 비율은 저장 자릿수 문자열")
    void hfChangesShareGroup() {
        service.updateGuarantee(ADMIN_ID, GuaranteeProvider.HF, new GuaranteeCriteriaUpdateRequest(
                new BigDecimal("85"), 700_000_000L, new BigDecimal("60"), false, REASON));

        List<CriteriaChangeHistory> rows = savedRows();
        assertThat(rows).extracting(CriteriaChangeHistory::getFieldName, CriteriaChangeHistory::getBeforeValue,
                        CriteriaChangeHistory::getAfterValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("collateralRatio", "90.00", "85.00"),
                        org.assertj.core.groups.Tuple.tuple("seniorDebtRatioLimit", null, "60.00"),
                        org.assertj.core.groups.Tuple.tuple("requiresLoanLink", "true", "false"));
        assertThat(rows).extracting(CriteriaChangeHistory::getChangeGroupId).containsOnly(rows.get(0).getChangeGroupId());
        assertThat(rows.get(2).getTarget()).isEqualTo(CriteriaTarget.HF_CRITERIA);
        assertThat(hfDetail.isLoanLinkedRequired()).isFalse();
    }

    @Test
    @DisplayName("HUG 의 requiresLoanLink 는 무시하고, 바뀐 필드가 없으면 이력을 저장하지 않는다")
    void noChangeNoHistory() {
        service.updateGuarantee(ADMIN_ID, GuaranteeProvider.HUG, new GuaranteeCriteriaUpdateRequest(
                new BigDecimal("90"), 700_000_000L, new BigDecimal("60.0"), true, REASON));

        verify(historyRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("담보인정비율을 깡통전세 선 이하로 내리면 400 collateralRatio")
    void collateralNotAboveNegativeEquity() {
        assertInvalid(() -> service.updateGuarantee(ADMIN_ID, GuaranteeProvider.HUG,
                new GuaranteeCriteriaUpdateRequest(new BigDecimal("80.00"), 1L, null, false, REASON)),
                "collateralRatio");
        verify(historyRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("위험 기준: 주의 선이 깡통전세 선 이상이면 400 cautionLeaseRatio")
    void cautionNotBelowNegativeEquity() {
        assertInvalid(() -> service.updateRiskThreshold(ADMIN_ID,
                new RiskThresholdUpdateRequest(new BigDecimal("75"), new BigDecimal("75"), REASON)),
                "cautionLeaseRatio");
    }

    @Test
    @DisplayName("위험 기준: 깡통전세 선이 기관 담보인정비율 최솟값 이상이면 400 negativeEquityRatio")
    void negativeEquityNotBelowMinCollateral() {
        ReflectionTestUtils.setField(hf, "collateralRatio", new BigDecimal("85.00"));

        assertInvalid(() -> service.updateRiskThreshold(ADMIN_ID,
                new RiskThresholdUpdateRequest(new BigDecimal("85"), new BigDecimal("70"), REASON)),
                "negativeEquityRatio");
    }

    @Test
    @DisplayName("위험 기준: 단조를 지키면 바뀐 필드만 이력, 대상 키는 RISK_CRITERIA")
    void riskThresholdUpdated() {
        service.updateRiskThreshold(ADMIN_ID,
                new RiskThresholdUpdateRequest(new BigDecimal("80"), new BigDecimal("65.5"), REASON));

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getTargetKey()).isEqualTo("RISK_CRITERIA");
            assertThat(row.getFieldName()).isEqualTo("cautionLeaseRatio");
            assertThat(row.getBeforeValue()).isEqualTo("70.00");
            assertThat(row.getAfterValue()).isEqualTo("65.50");
        });
        assertThat(risk.getCautionLeaseRatio()).isEqualByComparingTo("65.5");
    }

    @Test
    @DisplayName("보증료율: 요율만 바뀌고 대상 키는 기관/유형/보증금/부채비율, 소수 셋째 자리 표기")
    void premiumRateUpdated() {
        GuaranteePremiumRate rate = premiumRate(5L, 1L, "0.097");
        when(premiumRateRepository.findAllById(Set.of(5L))).thenReturn(List.of(rate));

        service.updatePremiumRates(ADMIN_ID, new PremiumRateUpdateRequest(
                List.of(new PremiumRateUpdateRequest.Rate(5L, new BigDecimal("0.115"))), REASON));

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getTarget()).isEqualTo(CriteriaTarget.GUARANTEE_PREMIUM_RATE);
            assertThat(row.getTargetKey()).isEqualTo("HUG/APARTMENT/0-200000000/0.00-80.00");
            assertThat(row.getBeforeValue()).isEqualTo("0.097");
            assertThat(row.getAfterValue()).isEqualTo("0.115");
        });
        assertThat(rate.getPremiumRate()).isEqualByComparingTo("0.115");
    }

    @Test
    @DisplayName("보증료율: 없는 식별자면 400 rates")
    void unknownPremiumRate() {
        when(premiumRateRepository.findAllById(Set.of(99L))).thenReturn(List.of());

        assertInvalid(() -> service.updatePremiumRates(ADMIN_ID, new PremiumRateUpdateRequest(
                List.of(new PremiumRateUpdateRequest.Rate(99L, BigDecimal.ONE)), REASON)), "rates");
    }

    @Test
    @DisplayName("보증료율: 같은 식별자가 두 번 오면 400 rates")
    void duplicatedPremiumRate() {
        assertInvalid(() -> service.updatePremiumRates(ADMIN_ID, new PremiumRateUpdateRequest(
                List.of(new PremiumRateUpdateRequest.Rate(5L, BigDecimal.ONE),
                        new PremiumRateUpdateRequest.Rate(5L, BigDecimal.TEN)), REASON)), "rates");
    }

    @Test
    @DisplayName("대출 규제: 바뀐 필드(보증금 비율 · 무주택 상한)만 이력, 대상 키는 지역/주택 유형, 같은 값 40.0 은 이력 없음")
    void loanRegulationRecordsOnlyChangedFields() {
        LoanRegulation regulation = loanRegulation(1L);
        when(loanRegulationRepository.findAllById(Set.of(1L))).thenReturn(List.of(regulation));

        service.updateLoanRegulations(ADMIN_ID, new LoanRegulationUpdateRequest(List.of(
                new LoanRegulationUpdateRequest.Regulation(1L, new BigDecimal("70.5"), 300_000_000L, 180_000_000L,
                        new BigDecimal("40.0"), new BigDecimal("3"), new BigDecimal("40.00"))), REASON));

        List<CriteriaChangeHistory> rows = savedRows();
        assertThat(rows).extracting(CriteriaChangeHistory::getFieldName, CriteriaChangeHistory::getBeforeValue,
                        CriteriaChangeHistory::getAfterValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("depositRatioLimit", "80.00", "70.50"),
                        org.assertj.core.groups.Tuple.tuple("guaranteeCapNoHouse", "400000000", "300000000"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getTarget()).isEqualTo(CriteriaTarget.LOAN_REGULATION);
            assertThat(row.getTargetId()).isEqualTo(1L);
            assertThat(row.getTargetKey()).isEqualTo("SEOUL_REGULATED/ALL");
            assertThat(row.getChangeGroupId()).isEqualTo(rows.get(0).getChangeGroupId());
        });
        assertThat(regulation.getDepositRatioLimit()).isEqualByComparingTo("70.50");
        assertThat(regulation.getGuaranteeCapNoHouse()).isEqualTo(300_000_000L);
        assertThat(regulation.getEffectiveDate()).isEqualTo(LocalDate.of(2025, 10, 29));
    }

    @Test
    @DisplayName("대출 규제: 바뀐 필드가 없으면 이력을 저장하지 않는다")
    void loanRegulationNoChangeNoHistory() {
        when(loanRegulationRepository.findAllById(Set.of(1L))).thenReturn(List.of(loanRegulation(1L)));

        service.updateLoanRegulations(ADMIN_ID, new LoanRegulationUpdateRequest(List.of(
                new LoanRegulationUpdateRequest.Regulation(1L, new BigDecimal("80"), 400_000_000L, 180_000_000L,
                        new BigDecimal("40"), new BigDecimal("3.0"), new BigDecimal("40"))), REASON));

        verify(historyRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("대출 규제: 없는 식별자면 400 regulations")
    void unknownLoanRegulation() {
        when(loanRegulationRepository.findAllById(Set.of(99L))).thenReturn(List.of());

        assertInvalid(() -> service.updateLoanRegulations(ADMIN_ID, new LoanRegulationUpdateRequest(
                List.of(sameLimits(99L)), REASON)), "regulations");
    }

    @Test
    @DisplayName("대출 규제: 같은 식별자가 두 번 오면 400 regulations")
    void duplicatedLoanRegulation() {
        assertInvalid(() -> service.updateLoanRegulations(ADMIN_ID, new LoanRegulationUpdateRequest(
                List.of(sameLimits(1L), sameLimits(1L)), REASON)), "regulations");
        verify(loanRegulationRepository, never()).findAllById(any());
    }

    @SuppressWarnings("unchecked")
    private List<CriteriaChangeHistory> savedRows() {
        ArgumentCaptor<List<CriteriaChangeHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode().name()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.getField()).isEqualTo(field);
                });
    }

    private static GuaranteeCriteria guarantee(long id, GuaranteeProvider provider, String collateral,
            long maxDeposit, String seniorLimit) {
        GuaranteeCriteria criteria = entity(GuaranteeCriteria.class);
        ReflectionTestUtils.setField(criteria, "guaranteeId", id);
        ReflectionTestUtils.setField(criteria, "provider", provider);
        ReflectionTestUtils.setField(criteria, "collateralRatio", new BigDecimal(collateral));
        ReflectionTestUtils.setField(criteria, "maxDeposit", maxDeposit);
        ReflectionTestUtils.setField(criteria, "seniorDebtRatioLimit",
                seniorLimit == null ? null : new BigDecimal(seniorLimit));
        return criteria;
    }

    private static GuaranteePremiumRate premiumRate(long id, long guaranteeId, String premium) {
        GuaranteePremiumRate rate = entity(GuaranteePremiumRate.class);
        ReflectionTestUtils.setField(rate, "premiumRateId", id);
        ReflectionTestUtils.setField(rate, "guaranteeId", guaranteeId);
        ReflectionTestUtils.setField(rate, "houseType", HouseType.APARTMENT);
        ReflectionTestUtils.setField(rate, "depositMin", 0L);
        ReflectionTestUtils.setField(rate, "depositMax", 200_000_000L);
        ReflectionTestUtils.setField(rate, "debtRatioMin", new BigDecimal("0.00"));
        ReflectionTestUtils.setField(rate, "debtRatioMax", new BigDecimal("80.00"));
        ReflectionTestUtils.setField(rate, "premiumRate", new BigDecimal(premium));
        return rate;
    }

    private static LoanRegulation loanRegulation(long id) {
        LoanRegulation regulation = entity(LoanRegulation.class);
        ReflectionTestUtils.setField(regulation, "regulationId", id);
        ReflectionTestUtils.setField(regulation, "houseType", "ALL");
        ReflectionTestUtils.setField(regulation, "regionType", "SEOUL_REGULATED");
        ReflectionTestUtils.setField(regulation, "depositRatioLimit", new BigDecimal("80.00"));
        ReflectionTestUtils.setField(regulation, "guaranteeCapNoHouse", 400_000_000L);
        ReflectionTestUtils.setField(regulation, "guaranteeCapOneHouse", 180_000_000L);
        ReflectionTestUtils.setField(regulation, "dsrLimit", new BigDecimal("40.00"));
        ReflectionTestUtils.setField(regulation, "stressDsrRate", new BigDecimal("3.00"));
        ReflectionTestUtils.setField(regulation, "dtiLimit", new BigDecimal("40.00"));
        ReflectionTestUtils.setField(regulation, "effectiveDate", LocalDate.of(2025, 10, 29));
        return regulation;
    }

    private static LoanRegulationUpdateRequest.Regulation sameLimits(long id) {
        return new LoanRegulationUpdateRequest.Regulation(id, new BigDecimal("80"), 400_000_000L, 180_000_000L,
                new BigDecimal("40"), new BigDecimal("3"), new BigDecimal("40"));
    }

    private static <T> T entity(Class<T> type) {
        return BeanUtils.instantiateClass(type);
    }
}
