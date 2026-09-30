package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.event.RiskGradeChangedEvent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
    private ApplicationEventPublisher eventPublisher;
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
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = serviceWithLedgerMode("mock");
    }

    private RiskAnalysisCommandService serviceWithLedgerMode(String buildingLedgerMode) {
        return new RiskAnalysisCommandService(registryCommandService, ledgerCommandService, propertyRepository,
                buildingRegistryRepository, ownershipHistoryRepository, mortgageHistoryRepository,
                buildingLedgerRepository, guaranteeCriteriaRepository, hfCriteriaRepository, sgiCriteriaRepository,
                premiumRateRepository, insuranceProductRepository, riskCriteriaRepository, riskAnalysisRepository,
                eventPublisher, mock(PlatformTransactionManager.class), buildingLedgerMode);
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
    @DisplayName("대장 위반건축물 확인 불가(null)는 가입 불가 사유가 아니다 — 3사 가입 가능 · SAFE")
    void unverifiedViolationDoesNotDisqualify() {
        givenSafeProperty();
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID).orElseThrow().getViolation()).thenReturn(null);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.insuranceEligible()).isTrue();
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(response.providers()).allSatisfy(provider -> assertThat(provider.failedConditions()).isEmpty());
    }

    @Test
    @DisplayName("대장 위반건축물이 참이면 3사 가입 불가 사유다 — 기준의 위반건축물 불가가 참일 때")
    void confirmedViolationDisqualifies() {
        givenSafeProperty();
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID).orElseThrow().getViolation()).thenReturn(true);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.insuranceEligible()).isFalse();
        assertThat(response.consistency().violationBuilding()).isTrue();
        assertThat(response.providers()).allSatisfy(provider -> assertThat(provider.failedConditions())
                .containsExactly(GuaranteeFailedCondition.VIOLATION_BUILDING));
    }

    @Test
    @DisplayName("대장 위반건축물 확인 불가(null)는 응답에도 null 그대로 나간다")
    void unverifiedViolationIsNullInResponse() {
        givenSafeProperty();
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID).orElseThrow().getViolation()).thenReturn(null);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        assertThat(service.analyze(PROPERTY_ID).consistency())
                .isEqualTo(new RiskResponse.Consistency(true, true, null, true));
    }

    @Test
    @DisplayName("대장 없음: 오류 없이 대장 없이 분석한다 — 대장 항목은 null, 가입 가능 · SAFE, 분석 행의 대장 ID 는 null")
    void analyzesWithoutLedger() {
        givenSafeProperty();
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, null, null, null));
        assertThat(response.insuranceEligible()).isTrue();
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(response.providers()).allSatisfy(provider -> assertThat(provider.failedConditions()).isEmpty());
        assertThat(capturedSave().getLedgerId()).isNull();
    }

    // ---------- Mock 대장(PROP-04) ----------
    //
    // 대장 연동 모드 × 저장된 대장 출처 → 판정 입력. 픽스처 대장은 위반건축물이 참(Mock 이 지어낸 값)이다 — 판정에 쓰이면
    // 3사 모두 VIOLATION_BUILDING 으로 가입 불가, 쓰이지 않으면 대장 항목 셋이 확인 불가(null)이고 SAFE 다.
    //
    // | 모드  | 저장된 대장        | 판정 입력   | consistency(명의, 주소, 위반, 면적) | 가입 · 등급            | 분석 행 대장 ID |
    // | real  | MOCK              | 대장 없음   | (true, null, null, null)           | 가능 · SAFE            | null           |
    // | real  | BUILDING_HUB      | 그 대장     | (true, true, true, true)           | 불가 · 위반건축물       | 8              |
    // | mock  | MOCK              | 그 대장     | (true, true, true, true)           | 불가 · 위반건축물       | 8              |
    // | fault | MOCK              | 그 대장     | (true, true, true, true)           | 불가 · 위반건축물       | 8              |
    // | real  | 없음              | 대장 없음   | (true, null, null, null)           | 가능 · SAFE            | null           |

    @Test
    @DisplayName("real 연동: Mock 대장은 대장 없음으로 본다 — 지어낸 위반건축물이 가입 불가 사유가 되지 않고 분석 행의 대장 ID 는 null")
    void realModeIgnoresMockLedger() {
        service = serviceWithLedgerMode("real");
        givenSafeProperty();
        givenStoredLedger(true);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, null, null, null));
        assertThat(response.insuranceEligible()).isTrue();
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(capturedSave().getLedgerId()).isNull();
    }

    @Test
    @DisplayName("real 연동: 건축HUB 대장은 그대로 판정 입력이다")
    void realModeUsesBuildingHubLedger() {
        service = serviceWithLedgerMode("real");
        givenSafeProperty();
        givenStoredLedger(false);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, true, true, true));
        assertThat(response.insuranceEligible()).isFalse();
        assertThat(response.providers()).allSatisfy(provider -> assertThat(provider.failedConditions())
                .containsExactly(GuaranteeFailedCondition.VIOLATION_BUILDING));
        assertThat(capturedSave().getLedgerId()).isEqualTo(LEDGER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mock", "fault"})
    @DisplayName("mock · fault 연동: Mock 대장이 곧 그 모드의 대장이라 판정 입력이다")
    void mockAndFaultModesUseMockLedger(String mode) {
        service = serviceWithLedgerMode(mode);
        givenSafeProperty();
        givenStoredLedger(true);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, true, true, true));
        assertThat(response.insuranceEligible()).isFalse();
        assertThat(capturedSave().getLedgerId()).isEqualTo(LEDGER_ID);
    }

    @Test
    @DisplayName("real 연동에서 대장이 없으면 대장 없음 경로 그대로다")
    void realModeWithoutLedger() {
        service = serviceWithLedgerMode("real");
        givenSafeProperty();
        when(buildingLedgerRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyze(PROPERTY_ID);

        assertThat(response.consistency()).isEqualTo(new RiskResponse.Consistency(true, null, null, null));
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(capturedSave().getLedgerId()).isNull();
    }

    @Test
    @DisplayName("대장 수집을 건너뛰는 분석: 등기만 수집하고 대장 수집은 부르지 않은 채 판정한다")
    void analyzeWithCollectedLedgerSkipsLedgerCollection() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.analyzeWithCollectedLedger(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verifyNoInteractions(ledgerCommandService);
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(capturedSave().getLedgerId()).isEqualTo(LEDGER_ID);
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
    @DisplayName("동시 첫 분석으로 최신 분석 유일 인덱스에 걸리면 다시 판정해 저장하지 않고 끝낸다")
    void concurrentFirstAnalysisIsRejudged() {
        givenSafeProperty();
        RiskAnalysis savedByOtherInstance = analysis(RiskGrade.SAFE, true, "50.00");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOtherInstance));
        when(riskAnalysisRepository.save(any())).thenThrow(new DataIntegrityViolationException("uq_risk_analysis_latest"));

        RiskResponse response = service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository, times(1)).save(any());
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
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
    @DisplayName("첫 분석은 이전 등급이 없어 등급 변경 이벤트를 발행하지 않는다")
    void firstAnalysisPublishesNoEvent() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("새 행을 남겨도 등급이 같으면 등급 변경 이벤트를 발행하지 않는다")
    void sameGradeNewRowPublishesNoEvent() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.SAFE, true, "49.99");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("등급이 바뀌면 이전 등급 · 새 등급 · 새 행 시각을 담아 이벤트를 저장 뒤에 발행한다")
    void gradeChangePublishesEvent() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.CAUTION, true, "50.00");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        InOrder order = inOrder(riskAnalysisRepository, eventPublisher);
        order.verify(riskAnalysisRepository).save(any());
        order.verify(eventPublisher).publishEvent(
                new RiskGradeChangedEvent(PROPERTY_ID, RiskGrade.CAUTION, RiskGrade.SAFE, NOW));
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
        when(ledger.getViolation()).thenReturn(false);
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

    /** 픽스처 대장의 출처와 위반건축물(참)을 정한다. 주소 · 면적은 등기와 같다. */
    private void givenStoredLedger(boolean mockSource) {
        BuildingLedger ledger = buildingLedgerRepository.findByPropertyId(PROPERTY_ID).orElseThrow();
        when(ledger.isMock()).thenReturn(mockSource);
        when(ledger.getViolation()).thenReturn(true);
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
