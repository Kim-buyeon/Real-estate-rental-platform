package com.duri.rentalplatform.domain.loan.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.lock.DistributedLock;
import com.duri.rentalplatform.domain.loan.repository.LoanProductRepository;
import com.duri.rentalplatform.domain.loan.vo.LoanProductRefreshReport;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.external.loanrate.BankLoanRate;
import com.duri.rentalplatform.external.loanrate.JeonseLoanRateClient;
import com.duri.rentalplatform.external.loanrate.LoanRateHouseType;
import com.duri.rentalplatform.external.loanrate.LoanRateQuery;
import java.time.YearMonth;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 대출 상품 금리 갱신(LOAN-01) — HF 전세자금대출 금리 API 에서 한 기준월의 은행별 금리를 받아 매물 유형마다 대출 상품 행에 반영한다.
 *
 * <p><b>트랜잭션</b> — 열지 않는다. 외부 호출을 끝낸 뒤 매물 유형마다 {@link LoanProductRefreshWriter} 가 한 경계로 쓴다.
 *
 * <p><b>한 번만</b> — 앱이 두 프로세스로 뜬다. 기준월 키 {@code loan:batch:rate-refresh:{yyyy-MM}} 의 분산 락을 기다리지 않고
 * 한 번만 시도해, 못 잡은 인스턴스는 {@code EXTERNAL_API_UNAVAILABLE} 을 받고 아무것도 하지 않는다. 만료는 짧게 잡고 도는
 * 동안 연장한다 — 잡은 인스턴스가 죽으면 연장이 멈춰 곧 풀린다. 반영은 자연키 갱신이라 락이 만료된 뒤 다시 돌아도 결과가 같다.
 *
 * <p><b>실패</b> — 한 매물 유형의 연동 실패 · 실적 없음은 그 유형만 건너뛰고 기존 행을 둔다. 데이터 적재 설계서 1.5 「실패해도
 * 기존 데이터를 훼손하지 않는다」.
 */
@Slf4j
@Service
public class LoanProductRefreshService {

    private final JeonseLoanRateClient jeonseLoanRateClient;
    private final LoanProductRefreshWriter writer;
    private final LoanProductRepository loanProductRepository;
    private final long productMaxLimit;

    public LoanProductRefreshService(JeonseLoanRateClient jeonseLoanRateClient, LoanProductRefreshWriter writer,
            LoanProductRepository loanProductRepository,
            @Value("${loan.product.hf-max-limit}") long productMaxLimit) {
        this.jeonseLoanRateClient = jeonseLoanRateClient;
        this.writer = writer;
        this.loanProductRepository = loanProductRepository;
        this.productMaxLimit = productMaxLimit;
    }

    /**
     * 한 기준월을 반영한다.
     *
     * @param baseMonth 금리 기준월. 락 키가 된다
     * @throws BusinessException {@link ErrorCode#EXTERNAL_API_UNAVAILABLE} — 같은 기준월의 락을 다른 인스턴스가 잡고 있을 때.
     *                           매물 유형별 연동 실패는 예외로 올리지 않고 집계에 남긴다
     */
    @DistributedLock(
            key = "'loan:batch:rate-refresh:' + #baseMonth",
            waitTimeout = "0s",
            leaseTime = "${loan.batch.rate-refresh.lock-lease-time}",
            renewInterval = "${loan.batch.rate-refresh.lock-renew-interval}")
    public LoanProductRefreshReport refresh(YearMonth baseMonth) {
        LoanProductRefreshReport report = new LoanProductRefreshReport();
        for (PropertyType houseType : PropertyType.values()) {
            List<BankLoanRate> rates;
            try {
                rates = jeonseLoanRateClient.findBankLoanRates(
                        new LoanRateQuery(baseMonth, LoanRateHouseType.valueOf(houseType.name())));
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                    throw e;
                }
                report.failed(houseType);
                continue;
            }
            if (rates.isEmpty()) {
                report.empty(houseType);
                continue;
            }
            report.refreshed(houseType, writer.write(houseType, baseMonth, rates, productMaxLimit));
        }
        log.info("[대출 금리 갱신] {} — {}", baseMonth, report.summary());
        return report;
    }

    /** 기준월 행이 없는 매물 유형이 하나라도 있는가. 기동 뒤 갱신 여부를 가른다 — 테이블이 비었거나 월 갱신을 놓친 경우다. */
    public boolean isStale(YearMonth baseMonth) {
        for (PropertyType houseType : PropertyType.values()) {
            if (!loanProductRepository.existsByHouseTypeAndBaseMonthGreaterThanEqual(houseType, baseMonth.atDay(1))) {
                return true;
            }
        }
        return false;
    }
}
