package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.cache.CriteriaVersionStore;
import com.duri.rentalplatform.common.cache.CriteriaVersionWatcher;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.entity.PropertyCode;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

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
    private JudgementCriteriaCache criteriaCache;
    private CriteriaVersionWatcher criteriaVersionWatcher;
    private PlatformTransactionManager transactionManager;

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
        criteriaVersionWatcher = mock(CriteriaVersionWatcher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = serviceWithLedgerMode("mock");
        // 판정 기록은 최신 행을 잠금 조회(findLatestForUpdate)로 읽는다. 각 테스트는 잠그지 않는 조회에 최신 행을 스텁하므로, 잠금
        // 조회가 그 스텁을 그대로 따르게 한다 — 스텁 순서(thenReturn 연쇄)도 두 조회가 호출 순서대로 함께 소비한다.
        when(riskAnalysisRepository.findLatestForUpdate(any())).thenAnswer(
                invocation -> riskAnalysisRepository.findByPropertyIdAndLatestTrue(invocation.getArgument(0)));
    }

    private RiskAnalysisCommandService serviceWithLedgerMode(String buildingLedgerMode) {
        // 기준표는 실제 캐시가 목 저장소에서 읽는다 — 첫 판정 때 읽으므로 각 테스트의 기준 스텁이 그대로 쓰인다.
        criteriaCache = new JudgementCriteriaCache(guaranteeCriteriaRepository,
                hfCriteriaRepository, sgiCriteriaRepository, premiumRateRepository, insuranceProductRepository,
                riskCriteriaRepository, new CriteriaVersionStore(mock(StringRedisTemplate.class)),
                mock(PlatformTransactionManager.class), buildingLedgerMode);
        return new RiskAnalysisCommandService(registryCommandService, ledgerCommandService, propertyRepository,
                buildingRegistryRepository, ownershipHistoryRepository, mortgageHistoryRepository,
                buildingLedgerRepository, riskAnalysisRepository, criteriaCache, criteriaVersionWatcher,
                JsonMapper.builder().build(),
                eventPublisher, transactionManager, buildingLedgerMode);
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

    // ---------- 저장된 판정 조회(findLatestOrAnalyze) ----------
    //
    // 저장된 판정을 돌려주는 조건은 셋이 모두 맞을 때다 — 근거 JSON 이 있다 · 근거를 읽을 수 있다 · 기준 지문이 지금과 같다 · 재분석 대기가
    // 아니다. 하나라도 어긋나면 수집 → 판정 경로로 가서 근거를 새로 적는다.
    //
    // | 최신 행 근거 | 지문   | JSON   | 재분석 대기 | 결과                                      |
    // | 있음        | 같음   | 정상   | 아니오      | 저장된 근거 반환 — 수집 · 판정 · 쓰기 없음  |
    // | 없음(null)  | -      | -      | 아니오      | 판정 경로 — 같은 결론이면 최신 행에 근거만 채움 |
    // | 있음        | 다름   | 정상   | 아니오      | 판정 경로                                 |
    // | 있음        | 같음   | 깨짐   | 아니오      | 판정 경로 — 같은 결론이면 근거를 새로 적음   |
    // | 있음        | 같음   | 정상   | 예          | 판정 경로 — 최신 행은 읽지도 않음           |
    // | 행 없음     | -      | -      | 아니오      | 판정 경로 — 새 최신 행                     |

    @Test
    @DisplayName("저장된 판정 적중: 수집 · 판정 입력 읽기 · 쓰기 없이 저장된 근거를 돌려주고, 매물과 최신 분석 행만 읽는다")
    void storedJudgementHitReadsOnlyPropertyAndLatestRow() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        RiskResponse judged = service.analyze(PROPERTY_ID);
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        assertThat(response).usingRecursiveComparison().ignoringFields("analyzedAt").isEqualTo(judged);
        assertThat(response.analyzedAt()).isEqualTo(OffsetDateTime.of(EARLIER, ZoneOffset.ofHours(9)));
        verifyNoInteractions(registryCommandService, ledgerCommandService, buildingRegistryRepository,
                ownershipHistoryRepository, mortgageHistoryRepository, buildingLedgerRepository, eventPublisher);
        verifyNoInteractions(transactionManager);
        verify(propertyRepository, times(1)).findById(PROPERTY_ID);
        verify(riskAnalysisRepository, times(1)).findByPropertyIdAndLatestTrue(PROPERTY_ID);
        verify(riskAnalysisRepository, never()).save(any());
        verify(riskAnalysisRepository, never()).flush();
    }

    @Test
    @DisplayName("저장된 판정 적중: 시세 · 기준일은 저장된 근거가 아니라 지금 매물의 값이다")
    void storedJudgementHitUsesCurrentPropertyPrice() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        clearInteractions();
        Property property = propertyRepository.findById(PROPERTY_ID).orElseThrow();
        when(property.getMarketPrice()).thenReturn(310_000_000L);
        when(property.getPriceDate()).thenReturn(LocalDate.of(2026, 7, 15));
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        assertThat(response.marketPrice()).isEqualTo(310_000_000L);
        assertThat(response.priceDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        verifyNoInteractions(registryCommandService, ledgerCommandService);
    }

    @Test
    @DisplayName("이미 읽은 매물을 받는 조회는 매물을 다시 읽지 않는다")
    void findLatestOrAnalyzeWithPropertyDoesNotReadPropertyAgain() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        clearInteractions();
        Property property = propertyRepository.findById(PROPERTY_ID).orElseThrow();
        clearInvocations(propertyRepository);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.findLatestOrAnalyze(property);

        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        verifyNoInteractions(propertyRepository);
    }

    @Test
    @DisplayName("매물이 없으면 PROPERTY_NOT_FOUND, 최신 분석 행도 읽지 않는다")
    void findLatestOrAnalyzePropertyNotFound() {
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLatestOrAnalyze(PROPERTY_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND));
        verifyNoInteractions(riskAnalysisRepository, registryCommandService);
    }

    @Test
    @DisplayName("최신 분석 행이 없으면 수집 → 판정하고 새 최신 행에 근거 · 기준 지문을 적는다")
    void noLatestRowJudgesAndRecords() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verify(ledgerCommandService).collectIfAbsent(PROPERTY_ID);
        RiskAnalysis saved = capturedSave();
        assertThat(saved.getCriteriaFingerprint()).isEqualTo(criteriaCache.current().fingerprint());
        assertThat(readSnapshot(saved)).isEqualTo(response.judgement());
    }

    @Test
    @DisplayName("기준 지문이 다르면 판정 경로로 가고 같은 결론이면 최신 행의 근거 · 지문만 새 기준으로 고친다")
    void fingerprintMismatchRejudgesAndRefreshesLatestRow() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        String currentSnapshot = stored.getJudgementSnapshot();
        stored.recordJudgement(currentSnapshot, "stale-fingerprint");
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verify(riskAnalysisRepository, never()).save(any());
        assertThat(stored.isLatest()).isTrue();
        assertThat(stored.getCriteriaFingerprint()).isEqualTo(criteriaCache.current().fingerprint());
        assertThat(stored.getJudgementSnapshot()).isEqualTo(currentSnapshot);
    }

    @Test
    @DisplayName("재분석 대기 매물은 최신 행을 읽지 않고 판정 경로로 간다")
    void reanalysisPendingRejudgesWithoutReadingStoredRow() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        clearInteractions();
        Property property = propertyRepository.findById(PROPERTY_ID).orElseThrow();
        when(property.isReanalysisPending()).thenReturn(true);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verify(mortgageHistoryRepository).findByRegistry(any());
        // 최신 행은 판정 경로의 저장 판단에서 한 번만 읽는다 — 조회 단계에서는 읽지 않았다.
        verify(riskAnalysisRepository, times(1)).findByPropertyIdAndLatestTrue(PROPERTY_ID);
    }

    @Test
    @DisplayName("최신 행에 근거가 없으면(V21 이전 행) 판정 경로로 가고 같은 결론이면 새 행 없이 근거 · 지문을 채운다")
    void rowWithoutSnapshotIsRejudgedAndFilled() {
        givenSafeProperty();
        RiskAnalysis legacy = analysis(RiskGrade.SAFE, true, "50.00");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(legacy));

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verify(riskAnalysisRepository, never()).save(any());
        assertThat(legacy.getCriteriaFingerprint()).isEqualTo(criteriaCache.current().fingerprint());
        assertThat(readSnapshot(legacy)).isEqualTo(response.judgement());
        assertThat(response.analyzedAt()).isEqualTo(OffsetDateTime.of(EARLIER, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("저장된 근거 JSON 이 깨졌으면 예외 없이 판정 경로로 가서 정상 근거로 덮어쓴다")
    void brokenSnapshotIsRejudgedAndOverwritten() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        String validSnapshot = stored.getJudgementSnapshot();
        stored.recordJudgement("{not json", criteriaCache.current().fingerprint());
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        assertThat(stored.getJudgementSnapshot()).isEqualTo(validSnapshot);
    }

    @Test
    @DisplayName("결론이 같고 근거만 다르면 새 행 없이 최신 행의 근거만 갱신하고 이벤트도 내지 않는다")
    void sameConclusionDifferentSnapshotUpdatesOnlySnapshot() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        String currentSnapshot = stored.getJudgementSnapshot();
        stored.recordJudgement("{\"outdated\":true}", criteriaCache.current().fingerprint());
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.analyze(PROPERTY_ID);

        assertThat(stored.getJudgementSnapshot()).isEqualTo(currentSnapshot);
        assertThat(stored.isLatest()).isTrue();
        verify(riskAnalysisRepository, never()).save(any());
        verify(riskAnalysisRepository, never()).flush();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("결론 · 근거 · 지문이 모두 같으면 아무것도 쓰지 않는다")
    void identicalJudgementWritesNothing() {
        givenSafeProperty();
        RiskAnalysis stored = spy(storedLatest());
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.analyze(PROPERTY_ID);

        verify(stored, never()).recordJudgement(any(), any());
        verify(stored, never()).supersede();
        verify(riskAnalysisRepository, never()).save(any());
        verify(riskAnalysisRepository, never()).flush();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("결론이 바뀌면 새 최신 행에 새 근거를 적고 기존 행은 이력으로 내린다")
    void changedConclusionRecordsNewSnapshotOnNewRow() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        String oldSnapshot = stored.getJudgementSnapshot();
        clearInteractions();
        Property property = propertyRepository.findById(PROPERTY_ID).orElseThrow();
        when(property.getDeposit()).thenReturn(280_000_000L);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.analyze(PROPERTY_ID);

        RiskAnalysis saved = capturedSave();
        assertThat(stored.isLatest()).isFalse();
        assertThat(saved.getJudgementSnapshot()).isNotEqualTo(oldSnapshot);
        assertThat(readSnapshot(saved)).isEqualTo(response.judgement());
        assertThat(saved.getCriteriaFingerprint()).isEqualTo(criteriaCache.current().fingerprint());
    }

    // ---------- 재분석 대기 내리기 · 지문 불일치 때 버전 확인 ----------

    @Test
    @DisplayName("판정에 성공하면 재분석 대기를 판정에 쓴 시세로 내린다")
    void successfulJudgementClearsReanalysisPendingWithJudgedMarketPrice() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        verify(propertyRepository).clearReanalysisPending(PROPERTY_ID, 300_000_000L);
        // 대조 — 판정 경로는 쓰기 트랜잭션을 연다(적중 경로 테스트가 열지 않음을 검증하는 것과 짝).
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    @DisplayName("같은 결론이라 새 행을 남기지 않는 판정도 재분석 대기를 내린다")
    void unchangedConclusionJudgementAlsoClearsReanalysisPending() {
        givenSafeProperty();
        RiskAnalysis latest = analysis(RiskGrade.SAFE, true, "50.0");
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(latest));

        service.analyze(PROPERTY_ID);

        verify(propertyRepository).clearReanalysisPending(PROPERTY_ID, 300_000_000L);
    }

    @Test
    @DisplayName("판정 저장이 예외로 끝나면 재분석 대기를 내리지 않는다")
    void failedJudgementDoesNotClearReanalysisPending() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.save(any())).thenThrow(new IllegalStateException("저장 실패"));

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID)).isInstanceOf(IllegalStateException.class);

        verify(propertyRepository, never()).clearReanalysisPending(any(), any());
    }

    @Test
    @DisplayName("판정 입력(등기)을 못 읽어 판정이 중단되면 재분석 대기를 내리지 않는다")
    void abortedJudgementDoesNotClearReanalysisPending() {
        givenSafeProperty();
        when(buildingRegistryRepository.findByPropertyId(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID)).isInstanceOf(BusinessException.class);

        verify(propertyRepository, never()).clearReanalysisPending(any(), any());
    }

    // ---------- 매물의 최신 판정 비정규화 열(V22) ----------

    @Test
    @DisplayName("첫 분석은 새 최신 행의 등급 · 전세가율(소수 둘째 자리)을 저장 뒤에 매물 열로 옮긴다")
    void firstAnalysisAppliesLatestJudgementToProperty() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        ArgumentCaptor<BigDecimal> ratio = ArgumentCaptor.forClass(BigDecimal.class);
        InOrder order = inOrder(riskAnalysisRepository, propertyRepository);
        order.verify(riskAnalysisRepository).save(any());
        order.verify(propertyRepository).applyLatestJudgement(eq(PROPERTY_ID), eq(RiskGrade.SAFE), ratio.capture());
        assertThat(ratio.getValue()).isEqualByComparingTo("50.00");
        assertThat(ratio.getValue().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("등급이 바뀐 새 최신 행은 새 등급을 매물 열로 옮긴다")
    void gradeChangeAppliesNewGradeToProperty() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID))
                .thenReturn(Optional.of(analysis(RiskGrade.CAUTION, true, "50.00")));
        givenSaveStampsCreatedAt();

        service.analyze(PROPERTY_ID);

        verify(propertyRepository).applyLatestJudgement(eq(PROPERTY_ID), eq(RiskGrade.SAFE), any());
    }

    @Test
    @DisplayName("결론이 같아 새 행을 남기지 않아도 최신 행의 등급 · 전세가율로 같은 조건부 UPDATE 를 부른다 — 값이 같으면 벌크 조건이 0행")
    void sameConclusionAlsoAppliesLatestRowToProperty() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID))
                .thenReturn(Optional.of(analysis(RiskGrade.SAFE, true, "50.0")));

        service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository, never()).save(any());
        ArgumentCaptor<BigDecimal> ratio = ArgumentCaptor.forClass(BigDecimal.class);
        verify(propertyRepository).applyLatestJudgement(eq(PROPERTY_ID), eq(RiskGrade.SAFE), ratio.capture());
        assertThat(ratio.getValue()).isEqualByComparingTo("50.00");
        assertThat(ratio.getValue().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("판정 기록은 최신 행을 잠금 조회로 읽고, 저장된 판정 적중 조회는 잠금 조회를 쓰지 않는다")
    void recordReadsLatestRowWithLockButStoredHitDoesNot() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID))
                .thenReturn(Optional.of(analysis(RiskGrade.SAFE, true, "50.0")));

        service.analyze(PROPERTY_ID);

        verify(riskAnalysisRepository).findLatestForUpdate(PROPERTY_ID);

        clearInvocations(riskAnalysisRepository);
        RiskAnalysis stored = storedLatest();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(riskAnalysisRepository, never()).findLatestForUpdate(any());
    }

    @Test
    @DisplayName("판정 저장이 예외로 끝나면 매물 열을 쓰지 않는다")
    void failedSaveDoesNotApplyToProperty() {
        givenSafeProperty();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        when(riskAnalysisRepository.save(any())).thenThrow(new IllegalStateException("저장 실패"));

        assertThatThrownBy(() -> service.analyze(PROPERTY_ID)).isInstanceOf(IllegalStateException.class);

        verify(propertyRepository, never()).applyLatestJudgement(any(), any(), any());
    }

    @Test
    @DisplayName("저장된 판정을 그대로 돌려줄 때는 재분석 대기를 건드리지 않는다")
    void storedJudgementHitDoesNotTouchReanalysisPending() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(propertyRepository, never()).clearReanalysisPending(any(), any());
        verify(propertyRepository, never()).markReanalysisPending(any());
    }

    @Test
    @DisplayName("지문이 같으면 버전 키를 확인하지 않는다")
    void matchingFingerprintDoesNotCheckVersion() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verifyNoInteractions(criteriaVersionWatcher);
    }

    @Test
    @DisplayName("지문이 다르고 조절된 확인이 새 기준을 읽어 지문이 같아지면 저장된 판정을 낸다 — 수집 · 판정 없음")
    void fingerprintMismatchResolvedByVersionCheckReturnsStoredJudgement() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        RiskCriteria riskCriteria = riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc().orElseThrow();
        // 다른 슬롯이 바뀐 기준(위험 기준 82)으로 판정해 지문을 적었다. 이 슬롯 캐시는 아직 옛 기준(80)을 든다.
        when(riskCriteria.getNegativeEquityRatio()).thenReturn(new BigDecimal("82.00"));
        criteriaCache.invalidate();
        String otherSlotFingerprint = criteriaCache.current().fingerprint();
        stored.recordJudgement(stored.getJudgementSnapshot(), otherSlotFingerprint);
        when(riskCriteria.getNegativeEquityRatio()).thenReturn(new BigDecimal("80.00"));
        criteriaCache.invalidate();
        assertThat(criteriaCache.current().fingerprint()).isNotEqualTo(otherSlotFingerprint);
        // 확인이 버전 변화를 보고 캐시를 다시 읽는 것을 흉내낸다 — 이 슬롯이 DB 의 새 기준(82)을 읽는다.
        when(criteriaVersionWatcher.reloadIfVersionChangedThrottled()).thenAnswer(invocation -> {
            when(riskCriteria.getNegativeEquityRatio()).thenReturn(new BigDecimal("82.00"));
            criteriaCache.invalidate();
            criteriaCache.reload();
            return true;
        });
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        RiskResponse response = service.findLatestOrAnalyze(PROPERTY_ID);

        verify(criteriaVersionWatcher).reloadIfVersionChangedThrottled();
        assertThat(response.riskGrade()).isEqualTo(RiskGrade.SAFE);
        verifyNoInteractions(registryCommandService, ledgerCommandService, buildingRegistryRepository);
        verify(riskAnalysisRepository, never()).save(any());
        verify(propertyRepository, never()).clearReanalysisPending(any(), any());
        verifyNoInteractions(transactionManager);
    }

    @Test
    @DisplayName("지문이 다르고 조절된 확인이 false 이면(막힘 · 변화 없음 · Redis 실패) 판정 경로로 간다")
    void fingerprintMismatchWithoutVersionChangeRejudges() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        stored.recordJudgement(stored.getJudgementSnapshot(), "stale-fingerprint");
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));
        when(criteriaVersionWatcher.reloadIfVersionChangedThrottled()).thenReturn(false);

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(criteriaVersionWatcher).reloadIfVersionChangedThrottled();
        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
        verify(propertyRepository).clearReanalysisPending(PROPERTY_ID, 300_000_000L);
    }

    @Test
    @DisplayName("지문이 다르고 확인이 true 여도 지문이 여전히 다르면 판정 경로로 간다")
    void fingerprintStillDifferentAfterVersionCheckRejudges() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        stored.recordJudgement(stored.getJudgementSnapshot(), "stale-fingerprint");
        clearInteractions();
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));
        when(criteriaVersionWatcher.reloadIfVersionChangedThrottled()).thenReturn(true);

        service.findLatestOrAnalyze(PROPERTY_ID);

        verify(registryCommandService).collectIfAbsent(PROPERTY_ID);
    }

    @Test
    @DisplayName("재분석 대기 매물은 지문을 보기 전에 판정 경로로 가므로 버전 키를 확인하지 않는다")
    void reanalysisPendingDoesNotCheckVersion() {
        givenSafeProperty();
        RiskAnalysis stored = storedLatest();
        Property property = propertyRepository.findById(PROPERTY_ID).orElseThrow();
        when(property.isReanalysisPending()).thenReturn(true);
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.of(stored));

        service.findLatestOrAnalyze(PROPERTY_ID);

        verifyNoInteractions(criteriaVersionWatcher);
    }

    /** 첫 분석을 실제로 돌려 저장된 최신 행(근거 · 지문 포함, 시각 EARLIER)을 만든다. 상호작용 기록은 비운다. */
    private RiskAnalysis storedLatest() {
        when(riskAnalysisRepository.findByPropertyIdAndLatestTrue(PROPERTY_ID)).thenReturn(Optional.empty());
        givenSaveStampsCreatedAt();
        service.analyze(PROPERTY_ID);
        RiskAnalysis saved = capturedSave();
        ReflectionTestUtils.setField(saved, "createdAt", EARLIER);
        clearInteractions();
        return saved;
    }

    private void clearInteractions() {
        clearInvocations(registryCommandService, ledgerCommandService, propertyRepository,
                buildingRegistryRepository, ownershipHistoryRepository, mortgageHistoryRepository,
                buildingLedgerRepository, riskAnalysisRepository, eventPublisher, transactionManager);
    }

    private static RiskResponse.Judgement readSnapshot(RiskAnalysis analysis) {
        return JsonMapper.builder().build().readValue(analysis.getJudgementSnapshot(), RiskResponse.Judgement.class);
    }

    private void givenSafeProperty() {
        Property property = mock(Property.class);
        PropertyCode typeCode = mock(PropertyCode.class);
        when(typeCode.getCodeValue()).thenReturn("APARTMENT");
        when(property.getPropertyTypeCode()).thenReturn(typeCode);
        when(property.getPropertyId()).thenReturn(PROPERTY_ID);
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
