package com.duri.rentalplatform.domain.risk.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.property.entity.BuildingLedger;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.repository.BuildingLedgerRepository;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.risk.calculator.DocumentConsistencyChecker;
import com.duri.rentalplatform.domain.risk.calculator.GuaranteeEligibilityJudge;
import com.duri.rentalplatform.domain.risk.calculator.NegativeEquityCalculator;
import com.duri.rentalplatform.domain.risk.calculator.RightViolationDetector;
import com.duri.rentalplatform.domain.risk.calculator.RiskGradeCalculator;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.entity.BuildingRegistry;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.GuaranteePremiumRate;
import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import com.duri.rentalplatform.domain.risk.entity.InsuranceProduct;
import com.duri.rentalplatform.domain.risk.entity.RiskAnalysis;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.entity.SgiCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
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
import com.duri.rentalplatform.domain.risk.vo.ConsistencyInput;
import com.duri.rentalplatform.domain.risk.vo.ConsistencyResult;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeInput;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeJudgementResult;
import com.duri.rentalplatform.domain.risk.vo.MortgageEntry;
import com.duri.rentalplatform.domain.risk.vo.NegativeEquityResult;
import com.duri.rentalplatform.domain.risk.vo.OwnershipRightEntry;
import com.duri.rentalplatform.domain.risk.vo.ProviderJudgement;
import com.duri.rentalplatform.domain.risk.vo.RightViolationResult;
import com.duri.rentalplatform.domain.risk.vo.RiskGradeResult;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 위험도 분석(RISK-01). 등기 · 대장을 수집하고 RISK-02 ~ 05 판정을 이어 등급을 정한 뒤, 결론이 바뀌었을 때만 이력을 남긴다.
 *
 * <p><b>트랜잭션</b> — 등기 · 대장 수집은 외부 호출을 포함하므로 이 서비스의 트랜잭션 밖에서 먼저 부른다(두 수집
 * 서비스는 각자 {@link TransactionTemplate} 으로 경계를 긋고, 여기서는 아무 트랜잭션도 열지 않은 상태로 부르므로
 * 중첩되지 않는다). 수집 실패(503)는 잡지 않고 올린다. 그 뒤 입력 읽기 · 판정 · 저장을 쓰기 트랜잭션 하나로 묶는다 —
 * 기존 최신 행을 내리는 UPDATE 와 새 행 INSERT 가 한 경계다. 방식은 등기 수집과 같은 이유로 코드로 긋는다.
 *
 * <p><b>저장 기준</b> — 최신 분석과 등급 · 3사 가입 · 저장 전세가율이 모두 같으면 저장하지 않는다. 다르거나 없으면
 * 기존 최신을 이력으로 내리고({@code is_latest = false}) 새 행을 최신으로 넣는다({@code previous_grade} = 이전 등급).
 *
 * <p><b>동시 분석</b> — 두 인스턴스가 같은 매물을 동시에 처음 분석하면 한쪽이 최신 분석 유일 인덱스
 * ({@code uq_risk_analysis_latest}, V9)에 걸린다. 그때는 한 번 다시 판정한다 — 다른 쪽이 같은 입력으로 저장했으므로
 * 「결론 같음 — 저장 안 함」으로 끝난다.
 */
@Service
public class RiskAnalysisCommandService {

    private final RegistryCommandService registryCommandService;
    private final LedgerCommandService ledgerCommandService;
    private final PropertyRepository propertyRepository;
    private final BuildingRegistryRepository buildingRegistryRepository;
    private final OwnershipHistoryRepository ownershipHistoryRepository;
    private final MortgageHistoryRepository mortgageHistoryRepository;
    private final BuildingLedgerRepository buildingLedgerRepository;
    private final GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private final HfCriteriaRepository hfCriteriaRepository;
    private final SgiCriteriaRepository sgiCriteriaRepository;
    private final GuaranteePremiumRateRepository premiumRateRepository;
    private final InsuranceProductRepository insuranceProductRepository;
    private final RiskCriteriaRepository riskCriteriaRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final TransactionTemplate writeTransaction;

    public RiskAnalysisCommandService(
            RegistryCommandService registryCommandService,
            LedgerCommandService ledgerCommandService,
            PropertyRepository propertyRepository,
            BuildingRegistryRepository buildingRegistryRepository,
            OwnershipHistoryRepository ownershipHistoryRepository,
            MortgageHistoryRepository mortgageHistoryRepository,
            BuildingLedgerRepository buildingLedgerRepository,
            GuaranteeCriteriaRepository guaranteeCriteriaRepository,
            HfCriteriaRepository hfCriteriaRepository,
            SgiCriteriaRepository sgiCriteriaRepository,
            GuaranteePremiumRateRepository premiumRateRepository,
            InsuranceProductRepository insuranceProductRepository,
            RiskCriteriaRepository riskCriteriaRepository,
            RiskAnalysisRepository riskAnalysisRepository,
            PlatformTransactionManager transactionManager) {
        this.registryCommandService = registryCommandService;
        this.ledgerCommandService = ledgerCommandService;
        this.propertyRepository = propertyRepository;
        this.buildingRegistryRepository = buildingRegistryRepository;
        this.ownershipHistoryRepository = ownershipHistoryRepository;
        this.mortgageHistoryRepository = mortgageHistoryRepository;
        this.buildingLedgerRepository = buildingLedgerRepository;
        this.guaranteeCriteriaRepository = guaranteeCriteriaRepository;
        this.hfCriteriaRepository = hfCriteriaRepository;
        this.sgiCriteriaRepository = sgiCriteriaRepository;
        this.premiumRateRepository = premiumRateRepository;
        this.insuranceProductRepository = insuranceProductRepository;
        this.riskCriteriaRepository = riskCriteriaRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 매물의 위험도를 분석하고 명세 1.1 응답으로 돌려준다.
     *
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물이 없을 때,
     *                           {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 등기 · 대장 수집이 실패했을 때
     */
    public RiskResponse analyze(Long propertyId) {
        registryCommandService.collectIfAbsent(propertyId);
        ledgerCommandService.collectIfAbsent(propertyId);
        try {
            return writeTransaction.execute(status -> judgeAndRecord(propertyId));
        } catch (DataIntegrityViolationException concurrentFirstAnalysis) {
            // 두 인스턴스가 같은 매물을 동시에 처음 분석하면 한쪽이 최신 분석 유일 인덱스(V9)에 걸린다.
            // 다른 쪽이 이미 같은 입력으로 저장했으므로 다시 판정하면 「결과 같음 — 저장 안 함」으로 끝난다.
            return writeTransaction.execute(status -> judgeAndRecord(propertyId));
        }
    }

    private RiskResponse judgeAndRecord(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        // 수집 직후라 없으면 수집 경로나 시드의 결함이다. 사용자 요청으로 생기는 상태가 아니므로 500 으로 낸다.
        BuildingRegistry registry = buildingRegistryRepository.findByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        BuildingLedger ledger = buildingLedgerRepository.findByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        RiskCriteria riskCriteria = riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        List<OwnershipRightEntry> ownerships = ownershipHistoryRepository.findByRegistry(registry).stream()
                .map(history -> new OwnershipRightEntry(history.getRankNo(), history.getRightType(),
                        history.getOwnerName(), history.isCurrent()))
                .toList();
        List<MortgageEntry> mortgages = mortgageHistoryRepository.findByRegistry(registry).stream()
                .map(history -> new MortgageEntry(history.getMaxBondAmount(), history.getPriorTenantDeposit(),
                        history.isSeniorDebt(), history.isActive()))
                .toList();

        long deposit = property.getDeposit();
        long marketPrice = property.getMarketPrice();

        NegativeEquityResult negativeEquity = NegativeEquityCalculator.calculate(
                mortgages, deposit, marketPrice, riskCriteria.getNegativeEquityRatio());
        RightViolationResult rights = RightViolationDetector.detect(ownerships);
        ConsistencyResult consistency = DocumentConsistencyChecker.check(new ConsistencyInput(
                ownerships, property.getLandlordName(), ledger.getLedgerAddress(), registry.getRegistryAddress(),
                ledger.getExclusiveArea(), registry.getExclusiveArea(), ledger.isViolation()));

        List<GuaranteeCriteria> guaranteeCriteria = guaranteeCriteriaRepository.findAll().stream()
                .sorted(Comparator.comparing(GuaranteeCriteria::getProvider))
                .toList();
        GuaranteeJudgementResult guarantee = GuaranteeEligibilityJudge.judge(
                new GuaranteeInput(marketPrice, deposit, negativeEquity.seniorDebtTotal(), houseType(property),
                        consistency.violationBuilding(), rights.rightViolations(), consistency.ownerNameMatched(),
                        consistency.addressMatched()),
                snapshots(guaranteeCriteria));

        RiskGradeResult grade = RiskGradeCalculator.calculate(
                negativeEquity.negativeEquity(), guarantee.insuranceEligible(),
                negativeEquity.seniorDebtTotal() + deposit, marketPrice, riskCriteria.getCautionLeaseRatio());

        LocalDateTime analyzedAt = record(propertyId, registry, ledger, negativeEquity, guarantee, grade,
                guaranteeCriteria);

        return RiskResponse.of(grade, negativeEquity, guarantee, rights, consistency,
                property.getMarketPrice(), property.getPriceType(), property.getPriceDate(), analyzedAt);
    }

    /** 결론이 바뀌었으면 새 최신 행을 남긴다. 최신 분석 행의 시각을 돌려준다. */
    private LocalDateTime record(Long propertyId, BuildingRegistry registry, BuildingLedger ledger,
            NegativeEquityResult negativeEquity, GuaranteeJudgementResult guarantee, RiskGradeResult grade,
            List<GuaranteeCriteria> guaranteeCriteria) {
        boolean hug = eligible(guarantee, GuaranteeProvider.HUG);
        boolean hf = eligible(guarantee, GuaranteeProvider.HF);
        boolean sgi = eligible(guarantee, GuaranteeProvider.SGI);

        Optional<RiskAnalysis> latest = riskAnalysisRepository.findByPropertyIdAndLatestTrue(propertyId);
        if (latest.isPresent()
                && latest.get().sameConclusion(grade.riskGrade(), hug, hf, sgi, negativeEquity.debtRatio())) {
            return latest.get().getCreatedAt();
        }

        RiskGrade previousGrade = latest.map(RiskAnalysis::getRiskGrade).orElse(null);
        latest.ifPresent(RiskAnalysis::supersede);
        // 내린 행을 먼저 반영한다. 새 행 INSERT 가 먼저 나가면 한순간 최신 행이 둘이다.
        riskAnalysisRepository.flush();

        RiskAnalysis saved = riskAnalysisRepository.save(RiskAnalysis.record(
                propertyId, registry.getRegistryId(), ledger.getLedgerId(),
                firstEligibleGuaranteeId(guarantee, guaranteeCriteria), negativeEquity.debtRatio(),
                hug, hf, sgi, grade.riskGrade(), grade.gradeReason(), previousGrade));
        return saved.getCreatedAt();
    }

    /** 요율 표 주택유형 — 아파트 외(오피스텔 등)는 OTHER. */
    private static HouseType houseType(Property property) {
        PropertyType type = PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue());
        return type == PropertyType.APARTMENT ? HouseType.APARTMENT : HouseType.OTHER;
    }

    /** 기관 기준 테이블들을 판정기 입력으로 묶는다. 넘긴 기관 순서(HUG → HF → SGI)가 응답 순서다. */
    private List<GuaranteeCriteriaSnapshot> snapshots(List<GuaranteeCriteria> criteria) {
        Map<Long, HfCriteria> hf = hfCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(HfCriteria::getGuaranteeId, Function.identity()));
        Map<Long, SgiCriteria> sgi = sgiCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(SgiCriteria::getGuaranteeId, Function.identity()));
        Map<Long, List<GuaranteePremiumRate>> rates = premiumRateRepository.findAll().stream()
                .collect(Collectors.groupingBy(GuaranteePremiumRate::getGuaranteeId));
        Map<Long, InsuranceProduct> products = insuranceProductRepository.findAll().stream()
                .collect(Collectors.toMap(InsuranceProduct::getGuaranteeId, Function.identity(),
                        (first, second) -> first));

        return criteria.stream()
                .map(row -> {
                    Long id = row.getGuaranteeId();
                    InsuranceProduct product = products.get(id);
                    return new GuaranteeCriteriaSnapshot(
                            row.getProvider(),
                            row.getCollateralRatio(),
                            row.getSeniorDebtRatioLimit(),
                            row.getMaxDeposit(),
                            sgi.containsKey(id) && sgi.get(id).isApartmentUnlimited(),
                            row.isViolationDisqualify(),
                            row.isRightViolationDisqualify(),
                            hf.containsKey(id) && hf.get(id).isLoanLinkedRequired(),
                            product == null ? null : product.getProductName(),
                            rates.getOrDefault(id, List.of()).stream().map(GuaranteePremiumRate::toBand).toList());
                })
                .toList();
    }

    private static boolean eligible(GuaranteeJudgementResult guarantee, GuaranteeProvider provider) {
        return guarantee.providers().stream()
                .anyMatch(judgement -> judgement.provider() == provider && judgement.eligible());
    }

    /** 가입 가능한 기관 중 HUG → HF → SGI 순 첫 기관의 기준 ID. 없으면 null. */
    private static Long firstEligibleGuaranteeId(GuaranteeJudgementResult guarantee,
            List<GuaranteeCriteria> criteria) {
        return guarantee.providers().stream()
                .filter(ProviderJudgement::eligible)
                .map(ProviderJudgement::provider)
                .sorted()
                .findFirst()
                .flatMap(provider -> criteria.stream().filter(row -> row.getProvider() == provider).findFirst())
                .map(GuaranteeCriteria::getGuaranteeId)
                .orElse(null);
    }
}
