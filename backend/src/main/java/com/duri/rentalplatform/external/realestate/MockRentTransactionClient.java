package com.duri.rentalplatform.external.realestate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 전월세 실거래가 Mock. 네트워크와 시각에 무관하게 <b>같은 조건이면 항상 같은 응답</b>을 준다.
 *
 * <p>난수 씨앗을 조회 조건에서만 만든다. 적재를 두 번 돌려도 같은 거래 목록이 나오므로 자연키 중복
 * 차단이 실제로 동작하는지 확인할 수 있다 — 씨앗이 시각에 걸리면 재실행마다 다른 매물이 생겨
 * 중복 차단을 검증할 수 없다.
 *
 * <p>법정동명을 「가상1동」처럼 둔 것은 의도다. 실제 동명을 쓰면 Mock 적재분이 실데이터처럼 보인다.
 * 시세 중앙값 산출에 필요한 것은 「같은 동끼리 묶인다」는 성질뿐이다.
 */
@Component
@ConditionalOnProperty(prefix = "external.rent-transaction", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockRentTransactionClient implements RentTransactionClient {

    /** 한 시군구 · 한 달에 만들어 내는 거래 건수. 면적 구간별 중앙값이 잡힐 만큼은 되어야 한다. */
    private static final int TRANSACTIONS_PER_MONTH = 48;

    private static final List<String> LEGAL_DONG_NAMES = List.of("가상1동", "가상2동", "가상3동");

    /** 전용면적(㎡) 후보. 면적 구간(AreaBand) 다섯 개에 고르게 걸치도록 골랐다. */
    private static final List<BigDecimal> AREA_CANDIDATES = List.of(
            new BigDecimal("29.50"), new BigDecimal("38.20"),
            new BigDecimal("45.90"), new BigDecimal("52.40"),
            new BigDecimal("59.80"), new BigDecimal("72.30"),
            new BigDecimal("84.90"), new BigDecimal("101.20"),
            new BigDecimal("114.70"), new BigDecimal("139.60"));

    /** 전세 보증금의 ㎡당 기준액(원). 실제 시세가 아니라 Mock 의 눈금이다. */
    private static final long DEPOSIT_PER_SQM = 6_000_000L;

    /** 월세 계약의 보증금 비율 하한 · 상한(전세 환산액 대비 %). */
    private static final int MONTHLY_DEPOSIT_MIN_PERCENT = 10;
    private static final int MONTHLY_DEPOSIT_MAX_PERCENT = 35;

    /** 월세 계약 비율(%). 전세만 있으면 계약 유형 분류가 한 갈래만 타게 된다. */
    private static final int MONTHLY_CONTRACT_PERCENT = 40;

    /** 금액 단위. 제공처가 만원 단위로 주므로 Mock 도 만원 단위로 떨어뜨린다. */
    private static final long AMOUNT_UNIT = 10_000L;

    @Override
    public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
        Random random = new Random(seedOf(query));
        YearMonth yearMonth = query.contractYearMonth();
        List<RentTransaction> transactions = new ArrayList<>(TRANSACTIONS_PER_MONTH);

        for (int index = 0; index < TRANSACTIONS_PER_MONTH; index++) {
            String legalDongName = LEGAL_DONG_NAMES.get(random.nextInt(LEGAL_DONG_NAMES.size()));
            BigDecimal areaSqm = AREA_CANDIDATES.get(random.nextInt(AREA_CANDIDATES.size()));
            long leaseEquivalent = roundToUnit(
                    areaSqm.multiply(BigDecimal.valueOf(DEPOSIT_PER_SQM)).longValue(), random);

            boolean monthlyContract = random.nextInt(100) < MONTHLY_CONTRACT_PERCENT;
            long deposit = leaseEquivalent;
            long monthlyRent = 0L;
            if (monthlyContract) {
                int depositPercent = MONTHLY_DEPOSIT_MIN_PERCENT
                        + random.nextInt(MONTHLY_DEPOSIT_MAX_PERCENT - MONTHLY_DEPOSIT_MIN_PERCENT + 1);
                deposit = roundToUnit(leaseEquivalent / 100 * depositPercent, random);
                // 남은 보증금을 월세로 환산한다. 전환율을 문서가 정하지 않아 Mock 안에서만 쓰는 눈금이다.
                monthlyRent = roundToUnit((leaseEquivalent - deposit) / 200, random);
            }

            transactions.add(new RentTransaction(
                    query.lawdCode(),
                    legalDongName,
                    "가상아파트 %d단지".formatted(random.nextInt(9) + 1),
                    "%d-%d".formatted(random.nextInt(900) + 100, random.nextInt(30) + 1),
                    areaSqm,
                    random.nextInt(20) + 1,
                    deposit,
                    monthlyRent,
                    yearMonth.atDay(random.nextInt(yearMonth.lengthOfMonth()) + 1),
                    1990 + random.nextInt(34),
                    query.buildingType(),
                    query.buildingType().getDataSource()));
        }
        return transactions;
    }

    /**
     * 조회 조건만으로 씨앗을 만든다. 문자열의 hashCode 는 자바 명세가 고정한 값이라 실행마다 같다.
     */
    private long seedOf(RentTransactionQuery query) {
        return ("%s|%s|%s".formatted(query.lawdCode(), query.contractYearMonth(), query.buildingType()))
                .hashCode();
    }

    /** 만원 단위로 떨어뜨리고 ±7% 안에서 흔든다. 같은 면적이 전부 같은 금액이면 중앙값이 무의미해진다. */
    private long roundToUnit(long amount, Random random) {
        long jittered = amount * (93 + random.nextInt(15)) / 100;
        return Math.max(AMOUNT_UNIT, jittered / AMOUNT_UNIT * AMOUNT_UNIT);
    }
}
