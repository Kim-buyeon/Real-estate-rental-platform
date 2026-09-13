package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.enums.AreaBand;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시세({@code market_price}) 산출. 같은 법정동 · 같은 면적대 <b>전세 실거래 보증금의 중앙값</b>이다.
 *
 * <p><b>왜 실거래 보증금을 그대로 쓰지 않는가</b> — 실거래가가 주는 값은 그 계약의 보증금이지 시세가
 * 아니다. 보증금에 배수를 곱해 시세를 만들면 깡통전세 판정(RISK-02)의 두 항 — 위험금액(선순위채권 +
 * 보증금)과 기준금액(시세 × 안전기준비율) — 이 보증금 하나에 묶여 모든 매물이 같은 결과로 고정된다.
 * 시세를 분모로 쓰는 전세가율({@code debtRatio} = (선순위채권 + 보증금) ÷ 주택가액)도 함께
 * 무의미해진다 — 비즈니스 로직 정의서 2장 · 4장. 지역 시세 통계({@code REGION_STATS})를 집계하는 배치는 아직
 * 없으므로 적재 시점에 같은 표본으로 직접 계산한다.
 *
 * <p><b>왜 평균이 아니라 중앙값인가</b> — 한 건의 초고가 거래가 평균을 끌어올리면 그 동네 전 매물의
 * 전세가율이 한꺼번에 낮아진다. 중앙값은 이상치에 흔들리지 않는다.
 *
 * <p><b>왜 전세 계약만 표본에 넣는가</b> — 월세 계약의 보증금은 시세와 무관하게 작다. 섞으면 중앙값이
 * 어느 쪽의 시세도 아니게 된다. 월세 · 반전세 매물의 시세도 같은 동 · 같은 면적대의 <b>전세</b>
 * 중앙값을 쓴다.
 *
 * <p><b>표본이 없으면 값을 만들지 않는다.</b> 법정동 표본이 없으면 자치구 표본으로 한 단계 넓히고,
 * 그것도 없으면 빈 값을 돌려준다. 적재는 그 매물을 건너뛰고 실패로 기록한다 — 시세는 판정의 입력이라
 * 지어낸 값을 넣으면 위험도가 조용히 틀어진다.
 */
public final class MarketPriceCalculator {

    /** 법정동 + 면적대 표본. */
    private final Map<DongAreaKey, Sample> byLegalDong;

    /** 면적대만으로 묶은 자치구 단위 표본. 법정동 표본이 없을 때 한 단계 넓힌다. */
    private final Map<AreaBand, Sample> byDistrict;

    private MarketPriceCalculator(Map<DongAreaKey, Sample> byLegalDong, Map<AreaBand, Sample> byDistrict) {
        this.byLegalDong = byLegalDong;
        this.byDistrict = byDistrict;
    }

    /**
     * 자치구 하나의 실거래 목록으로 시세표를 만든다.
     */
    public static MarketPriceCalculator from(List<RentTransaction> transactions) {
        Map<DongAreaKey, Sample> byLegalDong = new HashMap<>();
        Map<AreaBand, Sample> byDistrict = new HashMap<>();

        for (RentTransaction transaction : transactions) {
            if (!isLeaseContract(transaction)) {
                continue;
            }
            AreaBand areaBand = AreaBand.of(transaction.areaSqm());
            byLegalDong
                    .computeIfAbsent(new DongAreaKey(transaction.legalDongName(), areaBand),
                            key -> new Sample())
                    .add(transaction);
            byDistrict
                    .computeIfAbsent(areaBand, key -> new Sample())
                    .add(transaction);
        }
        return new MarketPriceCalculator(byLegalDong, byDistrict);
    }

    /**
     * 법정동과 전용면적에 해당하는 시세를 찾는다. 표본이 없으면 빈 값.
     */
    public Optional<MarketPrice> find(String legalDongName, BigDecimal areaSqm) {
        AreaBand areaBand = AreaBand.of(areaSqm);
        Sample sample = byLegalDong.get(new DongAreaKey(legalDongName, areaBand));
        if (sample == null) {
            sample = byDistrict.get(areaBand);
        }
        return Optional.ofNullable(sample).map(Sample::toMarketPrice);
    }

    private static boolean isLeaseContract(RentTransaction transaction) {
        return transaction.monthlyRent() == null || transaction.monthlyRent() == 0L;
    }

    /**
     * 산출된 시세.
     *
     * @param amount   중앙값(원)
     * @param baseDate 기준일. 표본에서 가장 최근 계약일이다. 화면에 시세 기준일로 표시한다
     */
    public record MarketPrice(Long amount, LocalDate baseDate) {
    }

    private record DongAreaKey(String legalDongName, AreaBand areaBand) {
    }

    /** 한 묶음의 표본. 보증금 목록과 가장 최근 계약일을 들고 있다. */
    private static final class Sample {
        private final List<Long> deposits = new ArrayList<>();
        private LocalDate latestContractDate;

        private void add(RentTransaction transaction) {
            deposits.add(transaction.deposit());
            if (latestContractDate == null || transaction.contractDate().isAfter(latestContractDate)) {
                latestContractDate = transaction.contractDate();
            }
        }

        private MarketPrice toMarketPrice() {
            return new MarketPrice(median(), latestContractDate);
        }

        /** 짝수 개면 가운데 두 값의 평균이다. 원 단위 정수라 나머지는 버린다. */
        private Long median() {
            List<Long> sorted = deposits.stream().sorted(Comparator.naturalOrder()).toList();
            int size = sorted.size();
            int middle = size / 2;
            if (size % 2 == 1) {
                return sorted.get(middle);
            }
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        }
    }
}
