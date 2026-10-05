package com.duri.rentalplatform.domain.loan.service;

import com.duri.rentalplatform.domain.loan.entity.LoanProduct;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanProductWriteResult;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import com.duri.rentalplatform.external.loanrate.BankLoanRate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 매물 유형의 은행별 금리를 대출 상품 행에 반영한다. 자연키는 (은행명, 매물 유형) — 없으면 넣고, 있으면 새 기준월일 때만 고친다.
 * 외부 호출은 {@link LoanProductRefreshService} 가 트랜잭션 밖에서 끝낸 뒤 값만 넘긴다.
 *
 * <p><b>API 가 주지 않는 상품 속성</b>
 * <ul>
 *   <li>상품명 · 유형 — HF 보증 전세자금대출. 제공처가 「한국주택금융공사 전세자금대출」 금리를 은행별로 공시한다
 *       (https://www.data.go.kr/data/15082044/openapi.do)
 *   <li>상환방식 BULLET — 1단계 한도 계산의 전제(비즈니스 로직 정의서 6-1장 「1단계는 만기일시 하나만」)
 *   <li>주택 보유 조건 TRUE — HF 일반전세자금보증 보증대상자 「본인과 배우자의 합산한 주택보유수가 1주택 이내」
 *       (https://www.hf.go.kr/ko/sub02/sub02_01_02.do, 확인일 2026-09-30)
 *   <li>소득 조건 · 금리 유형 · 대출 기간 — 같은 안내에 상품 값으로 적혀 있지 않아 NULL
 * </ul>
 *
 * <p><b>기준표 캐시 무효화</b> — 넣거나 고친 행이 있으면 커밋 뒤 판정 기준표 슬롯 캐시를 비우고 버전 키를 올린다. 대표 상품(한도
 * 계산의 금리 · 상품 한도)이 그 캐시에 있다. 바뀐 행이 없으면 걸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LoanProductRefreshWriter {

    static final String PRODUCT_NAME = "HF 보증 전세자금대출";
    static final String LOAN_TYPE = "JEONSE";
    static final String REPAYMENT_TYPE = "BULLET";
    static final boolean HOUSE_OWNERSHIP_ALLOWED = true;

    private final LoanProductRepository loanProductRepository;
    private final JudgementCriteriaCache judgementCriteriaCache;

    /**
     * @param rates    한 기준월의 은행별 금리. 비어 있지 않다 — 빈 결과는 호출부가 걸러 기존 행을 지킨다
     * @param maxLimit 상품 한도(원)
     */
    @Transactional
    public LoanProductWriteResult write(PropertyType houseType, YearMonth baseMonth, List<BankLoanRate> rates,
            long maxLimit) {
        LocalDate month = baseMonth.atDay(1);
        Map<String, LoanProduct> existing = loanProductRepository.findAllByHouseType(houseType).stream()
                .collect(Collectors.toMap(LoanProduct::getBankName, Function.identity()));

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (BankLoanRate rate : distinctByBank(rates).values()) {
            LoanProduct product = existing.get(rate.bankName());
            if (product == null) {
                loanProductRepository.save(LoanProduct.hfJeonseRate(rate.bankName(), PRODUCT_NAME, LOAN_TYPE,
                        REPAYMENT_TYPE, HOUSE_OWNERSHIP_ALLOWED, houseType, month, rate.weightedAverageRate(),
                        rate.loanAmount(), maxLimit));
                inserted++;
            } else if (product.getBaseMonth() == null || product.getBaseMonth().isBefore(month)) {
                // 수동으로 지난달을 다시 돌려도 더 늦은 기준월을 덮어쓰지 않는다.
                product.changeRate(month, rate.weightedAverageRate(), rate.loanAmount(), maxLimit);
                updated++;
            } else {
                unchanged++;
            }
        }
        if (inserted + updated > 0) {
            judgementCriteriaCache.invalidateAfterCommit();
        }
        return new LoanProductWriteResult(inserted, updated, unchanged);
    }

    /** 같은 은행이 두 번 오면 앞의 것만 쓴다 — 자연키 유일 인덱스에 걸려 그 유형 전체가 롤백되지 않게. */
    private static Map<String, BankLoanRate> distinctByBank(List<BankLoanRate> rates) {
        Map<String, BankLoanRate> byBank = new LinkedHashMap<>();
        rates.forEach(rate -> byBank.putIfAbsent(rate.bankName(), rate));
        return byBank;
    }
}
