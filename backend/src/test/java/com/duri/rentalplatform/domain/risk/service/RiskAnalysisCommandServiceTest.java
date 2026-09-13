package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import com.duri.rentalplatform.domain.risk.entity.InsuranceProduct;
import com.duri.rentalplatform.domain.risk.entity.OwnershipHistory;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.entity.SgiCriteria;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.repository.BuildingRegistryRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.InsuranceProductRepository;
import com.duri.rentalplatform.domain.risk.repository.MortgageHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.OwnershipHistoryRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskAnalysisRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.SgiCriteriaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link RiskAnalysisCommandService} 의 조립 · 저장 분기. 저장소 · 수집 서비스 · 트랜잭션 관리자를 목으로 둔다.
 *
 * <p>픽스처 매물 — 시세 3억 · 보증금 1.5억 · 을구 없음 · 명의 · 주소 · 면적 일치 · 아파트. 3사 모두 가입 가능하고
 * 전세가율 50.00 이라 SAFE 다. 판정 경계는 계산기 테스트가 맡고 여기서는 흐름만 본다.
 */
class RiskAnalysisCommandServiceTest {

    private static final long PROPERTY_ID = 1024L;
    private static final long REGISTRY_ID = 7L;
    private static final long LEDGER_ID = 8L;
    private static final long HUG_ID = 11L;
    private static final long HF_ID = 12L;
    private static final long SGI_ID = 13L;
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 7, 1, 9, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 3, 0);

    private RegistryCommandService registryCommandService;
    private LedgerCommandService ledgerCommandService;
    private PropertyRepository propertyRepository;
    private BuildingRegistryRepository buildingRegistryRepository;
    private OwnershipHistoryRepository ownershipHistoryRepository;
    private MortgageHistoryRepository mortgageHistoryRepository;
    private BuildingLedgerRepository buildingLedgerRepository;
    private GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private HfCriteriaRepository hfCriteriaRepository;
    private SgiCriteriaRepository sgiCriteriaRepository;
    private GuaranteePremiumRateRepository premiumRateRepository;
    private InsuranceProductRepository insuranceProductRepository;
    private RiskCriteriaRepository riskCriteriaRepository;
    private RiskAnalysisRepository riskAnalysisRepository;
    private RiskAnalysisCommandService service;

    @BeforeEach
    void setUp() {
        registryCommandService = mock(RegistryCommandService.class);
        ledgerCommandService = mock(LedgerCommandService.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingRegistryRepository = mock(BuildingRegistryRepository.class);
        ownershipHistoryRepository = mock(OwnershipHistoryRepository.class);
        mortgageHistoryRepository = mock(MortgageHistoryRepository.class);
        buildingLedgerRepository = mock(BuildingLedgerRepository.class);
        guaranteeCriteriaRepository = mock(GuaranteeCriteriaRepository.class);
        hfCriteriaRepository = mock(HfCriteriaRepository.class);
        sgiCriteriaRepository = mock(SgiCriteriaRepository.class);
        premiumRateRepository = mock(GuaranteePremiumRateRepository.class);
        insuranceProductRepository = mock(InsuranceProductRepository.class);
        riskCriteriaRepository = mock(RiskCriteriaRepository.class);
        riskAnalysisRepository = mock(RiskAnalysisRepository.class);
        service = new RiskAnalysisCommandService(registryCommandService, ledgerCommandService, propertyRepository,
                buildingRegistryRepository, ownershipHistoryRepository, mortgageHistoryRepository,
                buildingLedgerRepository, guaranteeCriteriaRepository, hfCriteriaRepository, sgiCriteriaRepository,
                premiumRateRepository, insuranceProductRepository, riskCriteriaRepository, riskAnalysisRepository,
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("첫 분석: 판정 근거를 조립해 응답하고 최신 행을 저장한다")
    void firstAnalysisSavesLatestRow() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(response.gradeReason()).isEqualTo(GradeReason.INSURANCE_ELIGIBLE);
        assertThat(response.debtRatio()).isEqualTo(new BigDecimal("50.00"));
        assertThat(response.marketPrice()).isEqualTo(300_000_000L);
        assertThat(response.priceType()).isEqualTo(PriceType.ACTUAL_TRANSACTION);
        assertThat(response.priceDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(response.seniorDebtTotal()).isZero();
        assertThat(response.isNegativeEquity()).isFalse();
        assertThat(response.insuranceEligible()).isTrue();
        // 기준 저장소가 SGI · HUG · HF 순으로 돌려줘도 응답은 HUG → HF → SGI 순이다.
        assertThat(response.providers()).extracting(RiskResponse.Provider::provider)
                .containsExactly(GuaranteeProvider.HUG, GuaranteeProvider.HF, GuaranteeProvider.SGI);
        assertThat(response.providers().get(1).loanLinkRequired()).isTrue();
        assertThat(response.providers().get(0).productName()).isEqualTo("전세보증금반환보증");
        assertThat(response.rightViolations()).isEmpty();
        assertThat(response.warnings()).containsExactly(OwnershipRightType.PROVISIONAL_REGISTRATION);
        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, true, false, true));
        assertThat(response.analyzedAt()).isEqualTo(OffsetDateTime.of(NOW, ZoneOffset.ofHours(9)));

        RiskAnalysis saved = capturedSave();
        assertThat(saved.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(saved.getRegistryId()).isEqualTo(REGISTRY_ID);
        assertThat(saved.getLedgerId()).isEqualTo(LEDGER_ID);
        assertThat(saved.getEligibleGuaranteeId()).isEqualTo(HUG_ID);
        assertThat(saved.getLeaseRatio()).isEqualByComparingTo("50.00");
        assertThat(saved.isHugEligible()).isTrue();
        assertThat(saved.isHfEligible()).isTrue();
        assertThat(saved.isSgiEligible()).isTrue();
        assertThat(saved.isInsuranceEligible()).isTrue();
        assertThat(saved.getRiskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(saved.getRiskReason()).isEqualTo("INSURANCE_ELIGIBLE");
        assertThat(saved.getPreviousGrade()).isNull();
        assertThat(saved.isLatest()).isTrue();
    }

    @Test
    @DisplayName("결론이 최신 분석과 같으면 저장하지 않고 최신 행의 시각을 낸다")
    void sameConclusionIsNotSaved() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.SAFE, true, "50.0");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));

        RiskResponse response = service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository, never()).save(any());
        assertThat(latest.isLatest()).isTrue();
        assertThat(response.analyzedAt()).isEqualTo(OffsetDateTime.of(EARLIER, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("전세가율만 달라져도 새 행을 남긴다")
    void leaseRatioChangeIsSaved() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.SAFE, true, "49.99");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        assertThat(capturedSave().getPreviousGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(latest.isLatest()).isFalse();
    }

    @Test
    @DisplayName("등급이 바뀌면 기존 최신을 내려 반영한 뒤 이전 등급을 담은 새 최신 행을 저장한다")
    void gradeChangeSupersedesLatest() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.CAUTION, true, "50.00");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(latest.isLatest()).isFalse();
        InOrder order = inOrder(riskAnalysisRepository);
        order.verify(riskAnalysisRepository).flush();
        order.verify(riskAnalysisRepository).save(any());
        RiskAnalysis saved = capturedSave();
        assertThat(saved.getPreviousGrade()).isEqualTo(RiskGrade.CAUTION);
        assertThat(saved.getRiskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(saved.isLatest()).isTrue();
        assertThat(response.analyzedAt()).isEqualTo(OffsetDateTime.of(NOW, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("등기 수집이 503 이면 그대로 올리고 대장 수집 · 판정 · 저장을 하지 않는다")
    void registryUnavailablePropagates() {
        doThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE))
                .when(registryCommandService).collectIfAbsent(PROPERTY_ID);

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        verifyNoInteractions(ledgerCommandService, riskAnalysisRepository);
    }

    @Test
    @DisplayName("대장 수집이 503 이면 그대로 올리고 저장하지 않는다")
    void ledgerUnavailablePropagates() {
        doThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE))
                .when(ledgerCommandService).collectIfAbsent(PROPERTY_ID);

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        verifyNoInteractions(riskAnalysisRepository);
    }

    @Test
    @DisplayName("매물이 없으면 수집 단계의 PROPERTY_NOT_FOUND 를 올린다")
    void propertyNotFoundPropagates() {
        doThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND))
                .when(registryCommandService).collectIfAbsent(PROPERTY_ID);

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    private void givenSafeProperty() {
        Property property = mock(Property.class);
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn("APARTMENT");
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(property.getLandlordName()).thenReturn("김임대");
        when(property.getDeposit()).thenReturn(150_000_000L);
        when(property.getMarketPrice()).thenReturn(300_000_000L);
        when(property.getPriceType()).thenReturn(PriceType.ACTUAL_TRANSACTION);
        when(property.getPriceDate()).thenReturn(LocalDate.of(2026, 6, 30));
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        BuildingRegistry registry = mock(BuildingRegistry.class);
        when(registry.getRegistryId()).thenReturn(REGISTRY_ID);
        when(registry.getRegistryAddress()).thenReturn("서울특별시 시험구 시험로 1");
        when(registry.getExclusiveArea()).thenReturn(new BigDecimal("84.00"));
        when(buildingRegistryRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(registry));

        // 목 생성(내부 스터빙)을 thenReturn 인자 안에서 하면 미완료 스터빙이 된다. 먼저 만든다.
        List<OwnershipHistory> ownerships = List.of(
                ownership(1, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대"),
                ownership(2, OwnershipRightType.PROVISIONAL_REGISTRATION, "박가등"));
        when(ownershipHistoryRepository.findByRegistry(registry)).thenReturn(ownerships);
        when(mortgageHistoryRepository.findByRegistry(registry)).thenReturn(List.of());

        BuildingLedger ledger = mock(BuildingLedger.class);
        when(ledger.getLedgerId()).thenReturn(LEDGER_ID);
        when(ledger.getLedgerAddress()).thenReturn("서울특별시 시험구 시험로 1");
        when(ledger.getExclusiveArea()).thenReturn(new BigDecimal("84.0"));
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.of(ledger));

        RiskCriteria riskCriteria = mock(RiskCriteria.class);
        when(riskCriteria.getNegativeEquityRatio()).thenReturn(new BigDecimal("80.00"));
        when(riskCriteria.getCautionLeaseRatio()).thenReturn(new BigDecimal("70.00"));
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenReturn(Optional.of(riskCriteria));

        List<GuaranteeCriteria> guaranteeCriteria = List.of(
                criteria(SGI_ID, GuaranteeProvider.SGI, 1_000_000_000L, null),
                criteria(HUG_ID, GuaranteeProvider.HUG, 700_000_000L, new BigDecimal("60.00")),
                criteria(HF_ID, GuaranteeProvider.HF, 700_000_000L, null));
        when(guaranteeCriteriaRepository.findAll()).thenReturn(guaranteeCriteria);
        HfCriteria hf = mock(HfCriteria.class);
        when(hf.getGuaranteeId()).thenReturn(HF_ID);
        when(hf.isLoanLinkedRequired()).thenReturn(true);
        when(hfCriteriaRepository.findAll()).thenReturn(List.of(hf));
        SgiCriteria sgi = mock(SgiCriteria.class);
        when(sgi.getGuaranteeId()).thenReturn(SGI_ID);
        when(sgi.isApartmentUnlimited()).thenReturn(true);
        when(sgiCriteriaRepository.findAll()).thenReturn(List.of(sgi));
        when(premiumRateRepository.findAll()).thenReturn(List.of());
        InsuranceProduct hugProduct = mock(InsuranceProduct.class);
        when(hugProduct.getGuaranteeId()).thenReturn(HUG_ID);
        when(hugProduct.getProductName()).thenReturn("전세보증금반환보증");
        when(insuranceProductRepository.findAll()).thenReturn(List.of(hugProduct));
    }

    private void givenSaveStampsCreatedAt() {
        when(riskAnalysisRepository.save(any())).thenAnswer(invocation -> {
            RiskAnalysis entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "createdAt", NOW);
            return entity;
        });
    }

    private RiskAnalysis capturedSave() {
        ArgumentCaptor<RiskAnalysis> captor = ArgumentCaptor.forClass(RiskAnalysis.class);
        verify(riskAnalysisRepository).save(captor.capture());
        return captor.getValue();
    }

    private static RiskAnalysis analysis(RiskGrade grade, boolean eligible, String leaseRatio) {
        RiskAnalysis analysis = RiskAnalysis.record(PROPERTY_ID, REGISTRY_ID, LEDGER_ID, HUG_ID,
                new BigDecimal(leaseRatio), eligible, eligible, eligible, grade, GradeReason.INSURANCE_ELIGIBLE,
                null);
        ReflectionTestUtils.setField(analysis, "createdAt", EARLIER);
        return analysis;
    }

    private static OwnershipHistory ownership(int rankNo, OwnershipRightType type, String holder) {
        OwnershipHistory history = mock(OwnershipHistory.class);
        when(history.getRankNo()).thenReturn(rankNo);
        when(history.getRightType()).thenReturn(type);
        when(history.getOwnerName()).thenReturn(holder);
        when(history.isCurrent()).thenReturn(true);
        return history;
    }

    private static GuaranteeCriteria criteria(long id, GuaranteeProvider provider, long maxDeposit,
            BigDecimal seniorDebtRatioLimit) {
        GuaranteeCriteria criteria = mock(GuaranteeCriteria.class);
        when(criteria.getGuaranteeId()).thenReturn(id);
        when(criteria.getProvider()).thenReturn(provider);
        when(criteria.getMaxDeposit()).thenReturn(maxDeposit);
        when(criteria.getCollateralRatio()).thenReturn(new BigDecimal("90.00"));
        when(criteria.getSeniorDebtRatioLimit()).thenReturn(seniorDebtRatioLimit);
        when(criteria.isViolationDisqualify()).thenReturn(true);
        when(criteria.isRightViolationDisqualify()).thenReturn(true);
        return criteria;
    }
}
