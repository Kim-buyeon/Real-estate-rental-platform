package com.duri.rentalplatform.domain.admin.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
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
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 기준 수정 — API 명세서(관리자) 1.1, 데이터베이스 설계서 31절.
 *
 * <p>한 요청이 한 트랜잭션이다. 값이 바뀐 필드만 이력 행을 만들고, 한 요청의 이력은 같은 {@code change_group_id} 로
 * 묶는다. 값 비교는 저장 자릿수로 맞춘 뒤 한다 — {@code 90.0} 과 {@code 90.00} 은 같은 값이다.
 *
 * <p>세 선 단조 {@code cautionLeaseRatio < negativeEquityRatio < min(collateralRatio)} 를 양쪽 수정에서 지킨다.
 * 위반은 400 {@code INVALID_REQUEST}.
 */
@Service
@RequiredArgsConstructor
public class CriteriaCommandService {

    private static final int RATIO_SCALE = 2;
    private static final int PREMIUM_RATE_SCALE = 3;
    private static final String RISK_TARGET_KEY = "RISK_CRITERIA";

    private final GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private final HfCriteriaRepository hfCriteriaRepository;
    private final GuaranteePremiumRateRepository premiumRateRepository;
    private final RiskCriteriaRepository riskCriteriaRepository;
    private final LoanRegulationRepository loanRegulationRepository;
    private final CriteriaChangeHistoryRepository historyRepository;

    /** 기관별 기준 수정. {@code requiresLoanLink} 는 HF 만 저장한다. */
    @Transactional
    public void updateGuarantee(Long adminId, GuaranteeProvider provider, GuaranteeCriteriaUpdateRequest request) {
        GuaranteeCriteria criteria = guaranteeCriteriaRepository.findByProvider(provider)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        BigDecimal collateralRatio = request.collateralRatio().setScale(RATIO_SCALE);
        BigDecimal seniorDebtRatioLimit = request.seniorDebtRatioLimit() == null
                ? criteria.getSeniorDebtRatioLimit()
                : request.seniorDebtRatioLimit().setScale(RATIO_SCALE);

        RiskCriteria risk = loadRiskCriteria();
        if (collateralRatio.compareTo(risk.getNegativeEquityRatio()) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "collateralRatio");
        }

        Changes changes = new Changes(adminId, request.changeReason());
        String key = provider.name();
        changes.decimal(CriteriaTarget.GUARANTEE_CRITERIA, criteria.getGuaranteeId(), key, "collateralRatio",
                criteria.getCollateralRatio(), collateralRatio);
        changes.amount(CriteriaTarget.GUARANTEE_CRITERIA, criteria.getGuaranteeId(), key, "maxDeposit",
                criteria.getMaxDeposit(), request.maxDeposit());
        changes.decimal(CriteriaTarget.GUARANTEE_CRITERIA, criteria.getGuaranteeId(), key, "seniorDebtRatioLimit",
                criteria.getSeniorDebtRatioLimit(), seniorDebtRatioLimit);
        if (changes.any()) {
            criteria.changeCriteria(collateralRatio, request.maxDeposit(), seniorDebtRatioLimit);
        }

        if (provider == GuaranteeProvider.HF) {
            HfCriteria hf = hfCriteriaRepository.findByGuaranteeId(criteria.getGuaranteeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
            if (hf.isLoanLinkedRequired() != request.requiresLoanLink()) {
                changes.add(CriteriaTarget.HF_CRITERIA, hf.getHfCriteriaId(), key, "requiresLoanLink",
                        String.valueOf(hf.isLoanLinkedRequired()), String.valueOf(request.requiresLoanLink()));
                hf.changeLoanLinkedRequired(request.requiresLoanLink());
            }
        }
        changes.save();
    }

    /** 요율 값만 수정한다. 없는 식별자 · 중복 식별자는 400. */
    @Transactional
    public void updatePremiumRates(Long adminId, PremiumRateUpdateRequest request) {
        Set<Long> ids = new HashSet<>();
        for (PremiumRateUpdateRequest.Rate rate : request.rates()) {
            if (!ids.add(rate.premiumRateId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "rates");
            }
        }
        Map<Long, GuaranteePremiumRate> rates = premiumRateRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(GuaranteePremiumRate::getPremiumRateId, Function.identity()));
        if (rates.size() != ids.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "rates");
        }
        Map<Long, GuaranteeProvider> providers = guaranteeCriteriaRepository.findAll().stream()
                .collect(Collectors.toMap(GuaranteeCriteria::getGuaranteeId, GuaranteeCriteria::getProvider));

        Changes changes = new Changes(adminId, request.changeReason());
        for (PremiumRateUpdateRequest.Rate requested : request.rates()) {
            GuaranteePremiumRate rate = rates.get(requested.premiumRateId());
            BigDecimal premiumRate = requested.premiumRate().setScale(PREMIUM_RATE_SCALE);
            if (changes.decimal(CriteriaTarget.GUARANTEE_PREMIUM_RATE, rate.getPremiumRateId(),
                    premiumRateKey(providers.get(rate.getGuaranteeId()), rate), "premiumRate",
                    rate.getPremiumRate(), premiumRate)) {
                rate.changePremiumRate(premiumRate);
            }
        }
        changes.save();
    }

    /** 대출 규제 수치 여섯만 수정한다. 없는 식별자 · 중복 식별자는 400. */
    @Transactional
    public void updateLoanRegulations(Long adminId, LoanRegulationUpdateRequest request) {
        Set<Long> ids = new HashSet<>();
        for (LoanRegulationUpdateRequest.Regulation regulation : request.regulations()) {
            if (!ids.add(regulation.regulationId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "regulations");
            }
        }
        Map<Long, LoanRegulation> regulations = loanRegulationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(LoanRegulation::getRegulationId, Function.identity()));
        if (regulations.size() != ids.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "regulations");
        }

        Changes changes = new Changes(adminId, request.changeReason());
        for (LoanRegulationUpdateRequest.Regulation requested : request.regulations()) {
            LoanRegulation regulation = regulations.get(requested.regulationId());
            BigDecimal depositRatioLimit = requested.depositRatioLimit().setScale(RATIO_SCALE);
            BigDecimal dsrLimit = requested.dsrLimit().setScale(RATIO_SCALE);
            BigDecimal stressDsrRate = requested.stressDsrRate().setScale(RATIO_SCALE);
            BigDecimal dtiLimit = requested.dtiLimit().setScale(RATIO_SCALE);

            Long id = regulation.getRegulationId();
            String key = loanRegulationKey(regulation);
            CriteriaTarget target = CriteriaTarget.LOAN_REGULATION;
            boolean changed = changes.decimal(target, id, key, "depositRatioLimit",
                    regulation.getDepositRatioLimit(), depositRatioLimit);
            changed |= changes.amount(target, id, key, "guaranteeCapNoHouse",
                    regulation.getGuaranteeCapNoHouse(), requested.guaranteeCapNoHouse());
            changed |= changes.amount(target, id, key, "guaranteeCapOneHouse",
                    regulation.getGuaranteeCapOneHouse(), requested.guaranteeCapOneHouse());
            changed |= changes.decimal(target, id, key, "dsrLimit", regulation.getDsrLimit(), dsrLimit);
            changed |= changes.decimal(target, id, key, "stressDsrRate",
                    regulation.getStressDsrRate(), stressDsrRate);
            changed |= changes.decimal(target, id, key, "dtiLimit", regulation.getDtiLimit(), dtiLimit);
            if (changed) {
                regulation.changeLimits(depositRatioLimit, requested.guaranteeCapNoHouse(),
                        requested.guaranteeCapOneHouse(), dsrLimit, stressDsrRate, dtiLimit);
            }
        }
        changes.save();
    }

    /** 위험 등급 기준 수정. 세 선 단조를 검증한다. */
    @Transactional
    public void updateRiskThreshold(Long adminId, RiskThresholdUpdateRequest request) {
        BigDecimal negativeEquityRatio = request.negativeEquityRatio().setScale(RATIO_SCALE);
        BigDecimal cautionLeaseRatio = request.cautionLeaseRatio().setScale(RATIO_SCALE);
        if (cautionLeaseRatio.compareTo(negativeEquityRatio) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cautionLeaseRatio");
        }
        BigDecimal minCollateralRatio = guaranteeCriteriaRepository.findAll().stream()
                .map(GuaranteeCriteria::getCollateralRatio)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        if (negativeEquityRatio.compareTo(minCollateralRatio) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "negativeEquityRatio");
        }

        RiskCriteria risk = loadRiskCriteria();
        Changes changes = new Changes(adminId, request.changeReason());
        changes.decimal(CriteriaTarget.RISK_CRITERIA, risk.getRiskCriteriaId(), RISK_TARGET_KEY,
                "negativeEquityRatio", risk.getNegativeEquityRatio(), negativeEquityRatio);
        changes.decimal(CriteriaTarget.RISK_CRITERIA, risk.getRiskCriteriaId(), RISK_TARGET_KEY,
                "cautionLeaseRatio", risk.getCautionLeaseRatio(), cautionLeaseRatio);
        if (changes.any()) {
            risk.changeThresholds(negativeEquityRatio, cautionLeaseRatio);
        }
        changes.save();
    }

    private RiskCriteria loadRiskCriteria() {
        return riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    /** 데이터베이스 설계서 31절의 사람이 읽는 대상 식별 — 기관/주택 유형/보증금 구간/부채비율 구간. */
    static String premiumRateKey(GuaranteeProvider provider, GuaranteePremiumRate rate) {
        return provider + "/" + rate.getHouseType() + "/"
                + rate.getDepositMin() + "-" + (rate.getDepositMax() == null ? "" : rate.getDepositMax()) + "/"
                + rate.getDebtRatioMin().toPlainString() + "-" + rate.getDebtRatioMax().toPlainString();
    }

    /** 데이터베이스 설계서 31절의 사람이 읽는 대상 식별 — 지역 유형/주택 유형. */
    static String loanRegulationKey(LoanRegulation regulation) {
        return regulation.getRegionType() + "/" + regulation.getHouseType();
    }

    /** 한 요청에서 바뀐 필드를 모아 같은 그룹 식별자로 저장한다. */
    private final class Changes {

        private final UUID groupId = UUID.randomUUID();
        private final Long adminId;
        private final String reason;
        private final List<CriteriaChangeHistory> rows = new ArrayList<>();

        private Changes(Long adminId, String reason) {
            this.adminId = adminId;
            this.reason = reason;
        }

        /** 비율. 저장 자릿수로 맞춘 값끼리 비교한다. 바뀌었으면 true. */
        boolean decimal(CriteriaTarget target, Long targetId, String key, String field,
                BigDecimal before, BigDecimal after) {
            boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
            if (same) {
                return false;
            }
            add(target, targetId, key, field, before == null ? null : before.toPlainString(), after.toPlainString());
            return true;
        }

        /** 금액. 정수 문자열. */
        boolean amount(CriteriaTarget target, Long targetId, String key, String field, Long before, Long after) {
            if (Objects.equals(before, after)) {
                return false;
            }
            add(target, targetId, key, field, before == null ? null : before.toString(), after.toString());
            return true;
        }

        void add(CriteriaTarget target, Long targetId, String key, String field, String before, String after) {
            rows.add(CriteriaChangeHistory.of(groupId, target, targetId, key, field, before, after, reason, adminId));
        }

        boolean any() {
            return !rows.isEmpty();
        }

        void save() {
            if (!rows.isEmpty()) {
                historyRepository.saveAll(rows);
            }
        }
    }
}
