package com.duri.rentalplatform.external.realestate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 매매 실거래가 Mock. 네트워크와 시각에 무관하게 <b>같은 조건이면 항상 같은 응답</b>을 준다 — 전월세 Mock
 * ({@link MockRentTransactionClient})과 같은 방식이다.
 *
 * <p>난수 씨앗을 조회 조건에서만 만든다. 갱신 배치를 두 번 돌려도 같은 표본이 나와 시세가 바뀌지 않으므로 「값이 같으면
 * 갱신하지 않는다」가 실제로 동작하는지 확인할 수 있다.
 *
 * <p>법정동명 · 전용면적 후보는 전월세 Mock 의 것을 그대로 쓴다. 시세는 같은 법정동 · 면적대 표본의 중앙값이라, 동명이나 면적이
 * 어긋나면 Mock 매물 전부가 자치구 표본으로 넓혀지거나 시세없음으로 빠진다.
 */
@Component
@ConditionalOnProperty(prefix = "external.sale-transaction", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockSaleTransactionClient implements SaleTransactionClient {

    /** 한 시군구 · 한 달에 만들어 내는 거래 건수. 면적 구간별 중앙값이 잡힐 만큼은 되어야 한다. */
    private static final int TRANSACTIONS_PER_MONTH = 24;

    /**
     * 매매가의 ㎡당 기준액(원). 실제 시세가 아니라 Mock 의 눈금이다. 전월세 Mock 의 전세 보증금 눈금(㎡당 600만 원)보다 크게
     * 두어 Mock 매물의 전세가율이 1 을 넘지 않게 한다 — 넘으면 Mock 매물이 전부 깡통전세로 판정되어 등급 분기가 한 갈래만 탄다.
     */
    private static final long PRICE_PER_SQM = 10_000_000L;

    /** 해제 거래 비율(%). 해제 거래를 표본에서 빼는 경로가 Mock 에서도 돌게 한다. */
    private static final int CANCELLED_PERCENT = 5;

    /** 금액 단위. 제공처가 만원 단위로 주므로 Mock 도 만원 단위로 떨어뜨린다. */
    private static final long AMOUNT_UNIT = 10_000L;

    /** 금액 흔들기 폭(%). 90~110 이면 ±10% 다. */
    private static final int JITTER_MIN_PERCENT = 90;
    private static final int JITTER_MAX_PERCENT = 110;

    /** 백분율 나눗수. */
    private static final int PERCENT_SCALE = 100;

    @Override
    public List<SaleTransaction> findSaleTransactions(SaleTransactionQuery query) {
        Random random = new Random(seedOf(query));
        YearMonth yearMonth = query.contractYearMonth();
        List<String> legalDongNames = MockRentTransactionClient.LEGAL_DONG_NAMES;
        List<BigDecimal> areaCandidates = MockRentTransactionClient.AREA_CANDIDATES;
        List<SaleTransaction> transactions = new ArrayList<>(TRANSACTIONS_PER_MONTH);

        for (int index = 0; index < TRANSACTIONS_PER_MONTH; index++) {
            String legalDongName = legalDongNames.get(random.nextInt(legalDongNames.size()));
            BigDecimal areaSqm = areaCandidates.get(random.nextInt(areaCandidates.size()));
            long dealAmount = roundToUnit(
                    areaSqm.multiply(BigDecimal.valueOf(PRICE_PER_SQM)).longValue(), random);

            transactions.add(new SaleTransaction(
                    query.lawdCode(),
                    legalDongName,
                    "가상아파트 %d단지".formatted(random.nextInt(9) + 1),
                    "%d-%d".formatted(random.nextInt(900) + 100, random.nextInt(30) + 1),
                    areaSqm,
                    random.nextInt(20) + 1,
                    dealAmount,
                    yearMonth.atDay(random.nextInt(yearMonth.lengthOfMonth()) + 1),
                    1990 + random.nextInt(34),
                    random.nextInt(PERCENT_SCALE) < CANCELLED_PERCENT,
                    query.buildingType(),
                    query.buildingType().getDataSource()));
        }
        return transactions;
    }

    /**
     * 조회 조건만으로 씨앗을 만든다. 전월세 Mock 과 같은 조건에서 같은 씨앗이 되지 않게 앞에 구분자를 붙인다.
     */
    private long seedOf(SaleTransactionQuery query) {
        return ("SALE|%s|%s|%s".formatted(query.lawdCode(), query.contractYearMonth(), query.buildingType()))
                .hashCode();
    }

    /** 만원 단위로 떨어뜨리고 흔들기 폭 안에서 금액을 흔든다. 같은 면적이 전부 같은 금액이면 중앙값이 무의미해진다. */
    private long roundToUnit(long amount, Random random) {
        int jitterPercent = JITTER_MIN_PERCENT
                + random.nextInt(JITTER_MAX_PERCENT - JITTER_MIN_PERCENT + 1);
        long jittered = amount * jitterPercent / PERCENT_SCALE;
        return Math.max(AMOUNT_UNIT, jittered / AMOUNT_UNIT * AMOUNT_UNIT);
    }
}
