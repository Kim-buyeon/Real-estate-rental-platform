package com.duri.rentalplatform.domain.risk.cache;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.cache.CriteriaSlotCache;
import com.duri.rentalplatform.common.cache.CriteriaVersionStore;
import com.duri.rentalplatform.domain.risk.calculator.CriteriaFingerprintCalculator;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.GuaranteePremiumRate;
import com.duri.rentalplatform.domain.risk.entity.HfCriteria;
import com.duri.rentalplatform.domain.risk.entity.InsuranceProduct;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.entity.SgiCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.InsuranceProductRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.SgiCriteriaRepository;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.JudgementCriteria;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 위험도 판정 기준표 슬롯 캐시 — 판정 기준 6종(보증 기준 · HF · SGI · 보증료율 · 보증 상품 · 위험 등급 기준)을 한 벌
 * ({@link JudgementCriteria})로 슬롯 메모리에 들고, 그 지문을 함께 계산한다. 공통 동작(요청 경로에서 Redis 를 부르지 않음 · 커밋 뒤
 * 무효화 · 버전 키)은 {@link CriteriaSlotCache}. 대출 한도 기준은 대출 쪽 캐시가 따로 든다.
 *
 * <p>무효화는 위험 등급 기준 · 보증 기준 · 보증료율 수정(ADMIN-01)이 건다.
 */
@Component
public class JudgementCriteriaCache extends CriteriaSlotCache<JudgementCriteria> {

    private final GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private final HfCriteriaRepository hfCriteriaRepository;
    private final SgiCriteriaRepository sgiCriteriaRepository;
    private final GuaranteePremiumRateRepository premiumRateRepository;
    private final InsuranceProductRepository insuranceProductRepository;
    private final RiskCriteriaRepository riskCriteriaRepository;
    private final String buildingLedgerMode;

    public JudgementCriteriaCache(
            GuaranteeCriteriaRepository guaranteeCriteriaRepository,
            HfCriteriaRepository hfCriteriaRepository,
            SgiCriteriaRepository sgiCriteriaRepository,
            GuaranteePremiumRateRepository premiumRateRepository,
            InsuranceProductRepository insuranceProductRepository,
            RiskCriteriaRepository riskCriteriaRepository,
            CriteriaVersionStore versionStore,
            PlatformTransactionManager transactionManager,
            @Value("${external.building-ledger.mode:mock}") String buildingLedgerMode) {
        super(versionStore, transactionManager);
        this.guaranteeCriteriaRepository = guaranteeCriteriaRepository;
        this.hfCriteriaRepository = hfCriteriaRepository;
        this.sgiCriteriaRepository = sgiCriteriaRepository;
        this.premiumRateRepository = premiumRateRepository;
        this.insuranceProductRepository = insuranceProductRepository;
        this.riskCriteriaRepository = riskCriteriaRepository;
        this.buildingLedgerMode = buildingLedgerMode;
    }

    /**
     * {@inheritDoc}
     *
     * @throws BusinessException {@link ErrorCode#INTERNAL_ERROR} — 위험 등급 기준 행이 없을 때(시드 결함)
     */
    @Override
    protected JudgementCriteria load() {
        RiskCriteria risk = riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        // 응답의 기관 순서가 HUG → HF → SGI 다. 저장소가 돌려주는 순서에 기대지 않는다.
        List<GuaranteeCriteria> guaranteeRows = guaranteeCriteriaRepository.findAll().stream()
                .sorted(Comparator.comparing(GuaranteeCriteria::getProvider))
                .toList();
        List<GuaranteeCriteriaSnapshot> guaranteeCriteria = snapshots(guaranteeRows);
        Map<GuaranteeProvider, Long> guaranteeIds = guaranteeRows.stream()
                .collect(Collectors.toMap(GuaranteeCriteria::getProvider, GuaranteeCriteria::getGuaranteeId));
        String fingerprint = CriteriaFingerprintCalculator.calculate(guaranteeCriteria, guaranteeIds,
                risk.getNegativeEquityRatio(), risk.getCautionLeaseRatio(), buildingLedgerMode);
        return new JudgementCriteria(guaranteeCriteria, guaranteeIds, risk.getNegativeEquityRatio(),
                risk.getCautionLeaseRatio(), fingerprint);
    }

    /** 기관 기준 테이블들을 판정기 입력으로 묶는다. 넘긴 기관 순서가 응답 순서다. */
    private List<GuaranteeCriteriaSnapshot> snapshots(List<GuaranteeCriteria> criteria) {
        Map<Long, HfCriteria> hf = hfCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(HfCriteria::getGuaranteeId, Function.identity()));
        Map<Long, SgiCriteria> sgi = sgiCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(SgiCriteria::getGuaranteeId, Function.identity()));
        // 요율 구간은 식별자 순으로 둔다. 판정기는 맞는 첫 구간을 고르고 지문도 이 순서로 펴므로, 저장소 순서에 따라 지문이
        // 슬롯마다 달라지지 않게 한다.
        Map<Long, List<GuaranteePremiumRate>> rates = premiumRateRepository.findAll().stream()
                .sorted(Comparator.comparing(GuaranteePremiumRate::getPremiumRateId))
                .collect(Collectors.groupingBy(GuaranteePremiumRate::getGuaranteeId));
        // 한 기관에 상품이 여럿이면 먼저 들어온 행을 쓴다.
        Map<Long, InsuranceProduct> products = insuranceProductRepository.findAll().stream()
                .sorted(Comparator.comparing(InsuranceProduct::getInsuranceId))
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
}
