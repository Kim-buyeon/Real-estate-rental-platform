package com.duri.rentalplatform.domain.loan.cache;

import com.duri.rentalplatform.common.cache.CriteriaSlotCache;
import com.duri.rentalplatform.common.cache.CriteriaVersionStore;
import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.entity.LoanRegulation;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.repository.LoanRegulationRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanCriteria;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 대출 한도 기준표 슬롯 캐시 — 최신 대출 규제 한 행과 매물 유형별 대표 대출 상품을 한 벌({@link LoanCriteria})로 슬롯 메모리에 든다.
 * 공통 동작(요청 경로에서 Redis 를 부르지 않음 · 커밋 뒤 무효화 · 버전 키)은 {@link CriteriaSlotCache}. 위험도 판정 기준과 같은 버전
 * 키를 쓰므로 어느 쪽 기준이 바뀌어도 두 캐시 모두 다시 읽는다.
 *
 * <p>무효화는 대출 규제 수정(ADMIN-01)과 대출 금리 갱신이 건다. 규제 행이 없거나 그 유형의 대표 상품이 없으면 그 유형을 뺀다 —
 * 한도 조회가 500 으로 낸다.
 */
@Component
public class LoanCriteriaCache extends CriteriaSlotCache<LoanCriteria> {

    private final LoanRegulationRepository loanRegulationRepository;
    private final LoanProductRepository loanProductRepository;

    public LoanCriteriaCache(
            LoanRegulationRepository loanRegulationRepository,
            LoanProductRepository loanProductRepository,
            CriteriaVersionStore versionStore,
            PlatformTransactionManager transactionManager) {
        super(versionStore, transactionManager);
        this.loanRegulationRepository = loanRegulationRepository;
        this.loanProductRepository = loanProductRepository;
    }

    @Override
    protected LoanCriteria load() {
        Optional<LoanRegulation> regulation =
                loanRegulationRepository.findFirstByOrderByEffectiveDateDescRegulationIdDesc();
        Map<PropertyType, LoanLimitCriteria> byType = new EnumMap<>(PropertyType.class);
        if (regulation.isEmpty()) {
            return new LoanCriteria(byType);
        }
        LoanRegulation rule = regulation.get();
        for (PropertyType type : PropertyType.values()) {
            representativeProduct(type).ifPresent(product -> byType.put(type, new LoanLimitCriteria(
                    rule.getDepositRatioLimit(), rule.getGuaranteeCapNoHouse(), rule.getGuaranteeCapOneHouse(),
                    rule.getDsrLimit(), rule.getStressDsrRate(), product.getInterestRate(), product.getMaxLimit())));
        }
        return new LoanCriteria(byType);
    }

    /** 매물 유형의 HF 금리 API 대표 행, 없으면 시드 예시 행. 선택 규칙은 {@link LoanProductRepository}. */
    private Optional<LoanProduct> representativeProduct(PropertyType propertyType) {
        return loanProductRepository
                .findFirstByHouseTypeOrderByBaseMonthDescLoanAmountDescInterestRateAscLoanIdAsc(propertyType)
                .or(loanProductRepository::findFirstByHouseTypeIsNullOrderByLoanIdAsc);
    }
}
