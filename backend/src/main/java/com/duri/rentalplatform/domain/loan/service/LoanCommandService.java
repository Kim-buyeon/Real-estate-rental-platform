package com.duri.rentalplatform.domain.loan.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.cache.LoanCriteriaCache;
import com.duri.rentalplatform.domain.loan.calculator.JeonseLoanLimitCalculator;
import com.duri.rentalplatform.domain.loan.dto.response.LoanLimitResponse;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitInput;
import com.duri.rentalplatform.domain.property.entity.Property;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.repository.PropertyRepository;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 전세자금대출 한도 조회(LOAN-01) — API 명세서(대출) 1.1, 비즈니스 로직 정의서 6장.
 *
 * <p><b>조회 요청이지만 Command 서비스다.</b> 가입 여부를 위험도 조회(RISK-01)와 같은 길로 얻는다
 * ({@link RiskAnalysisCommandService#findLatestOrAnalyze(Property)}). 저장된 판정을 쓸 수 없으면 그 자리에서 판정하고, 판정은
 * 결론이 바뀌면 이력을 쓴다. 조회 서비스가 저장을 일으키지 않도록 이쪽에 둔다 — GET 을 {@code RiskAnalysisCommandService} 로
 * 처리하는 RISK-01 과 같은 배치다. 가입 여부 하나만 분석 행에서 따로 읽지 않는 이유 — 저장된 판정이 아직 맞는지 가르는 규칙이
 * 두 곳에 생긴다.
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 판정은 등기 · 대장 외부 수집을 트랜잭션 밖에서 부르고 자기 쓰기 경계를 스스로 긋는다. 여기서
 * 읽기 전용 트랜잭션을 열면 외부 호출이 그 안에 들고 판정의 쓰기가 읽기 전용 경계에 합류한다. 나머지 조회는 저장소 기본
 * 트랜잭션으로 한 건씩 읽는다.
 *
 * <p>순서 — 매물(404) → 자격 정보(422 {@code PROFILE_INCOMPLETE}) → 가입 여부(422 {@code LOAN_PROPERTY_NOT_ELIGIBLE}) →
 * 기준값 → 계산. 자격 정보 확인을 가입 여부보다 앞에 두어 판정 · 외부 수집 없이 거를 수 있는 것을 먼저 거른다. 매물은 처음 한
 * 번만 읽고 위험도 조회에 넘긴다.
 *
 * <p>대출 규제 · 금리 · 상품 한도는 대출 기준 슬롯 캐시({@link LoanCriteriaCache})에서 읽는다. 매물 유형의 대표 상품 선택
 * 규칙은 {@code LoanProductRepository} 가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class LoanCommandService {

    static final String ANNUAL_INCOME = "annualIncome";

    private final UserQueryService userQueryService;
    private final RiskAnalysisCommandService riskAnalysisCommandService;
    private final PropertyRepository propertyRepository;
    private final LoanCriteriaCache loanCriteriaCache;

    /**
     * @throws BusinessException {@link ErrorCode#PROPERTY_NOT_FOUND} — 매물 없음,
     *                           {@link ErrorCode#PROFILE_INCOMPLETE} — 주택 보유자인데 연소득 없음({@code field} =
     *                           annualIncome), {@link ErrorCode#LOAN_PROPERTY_NOT_ELIGIBLE} — 보증보험 가입 불가 매물
     */
    public LoanLimitResponse calculateLimit(Long userId, Long propertyId) {
        Property property = propertyRepository.findWithPropertyTypeCodeByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        ProfileResponse.Profile profile = userQueryService.getProfile(userId).profile();
        boolean hasHouse = Boolean.TRUE.equals(profile.hasHouse());
        long annualIncome = orZero(profile.annualIncome());
        if (hasHouse && annualIncome <= 0) {
            throw new BusinessException(ErrorCode.PROFILE_INCOMPLETE, ANNUAL_INCOME);
        }

        if (!riskAnalysisCommandService.findLatestOrAnalyze(property).insuranceEligible()) {
            throw new BusinessException(ErrorCode.LOAN_PROPERTY_NOT_ELIGIBLE);
        }

        // 규제 행이나 그 유형의 대표 상품이 없으면 시드 결함이다. 사용자 요청으로 생기는 상태가 아니므로 500 으로 낸다.
        LoanLimitCriteria criteria = loanCriteriaCache.current()
                .findLoanLimitCriteria(PropertyType.valueOf(property.getPropertyTypeCode().getCodeValue()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        return LoanLimitResponse.from(JeonseLoanLimitCalculator.calculate(
                new LoanLimitInput(hasHouse, annualIncome, orZero(profile.existingLoanAnnualPayment()),
                        property.getDeposit()),
                criteria));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
