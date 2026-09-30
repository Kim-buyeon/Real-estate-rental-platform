package com.duri.rentalplatform.domain.property.calculator;

import com.duri.rentalplatform.domain.property.enums.AreaBand;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.external.realestate.SaleBuildingType;
import com.duri.rentalplatform.external.realestate.SaleTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시세({@code market_price}) 산출. 같은 매물 유형 · 같은 법정동 · 같은 면적대 <b>매매 실거래가의 중앙값</b>이다.
 *
 * <p><b>왜 매매 실거래가인가</b> — 이 값은 깡통전세 판정(RISK-02)의 주택시세이자 전세가율의 분모인 주택가액이다(비즈니스 로직
 * 정의서 2장 · 4장, 둘은 같은 {@code PROPERTY.market_price}). 주택가액은 그 집을 팔면 받는 값이므로 매매가에서 와야 한다. 전세
 * 보증금으로 만들면 분자(보증금)와 분모가 같은 시장에서 나와 전세가율이 늘 1 근처에 묶이고, 깡통전세 판정이 매물과 무관하게
 * 한쪽으로 쏠린다. 지역 시세 통계({@code REGION_STATS})를 집계하는 배치는 아직 없으므로 적재 시점에 표본으로 직접 계산한다.
 *
 * <p><b>왜 유형을 나누는가</b> — 같은 동 · 같은 면적대라도 오피스텔 매매가는 아파트보다 크게 낮다. 섞으면 두 유형이 함께 있는
 * 동에서 중앙값이 어느 쪽 시세도 아니게 된다. 그래서 아파트 매물은 아파트 매매만, 오피스텔 매물은 오피스텔 매매만 표본으로
 * 쓰고, 자치구로 넓힐 때도 유형은 섞지 않는다.
 *
 * <p><b>왜 평균이 아니라 중앙값인가</b> — 한 건의 초고가 거래가 평균을 끌어올리면 그 동네 전 매물의 전세가율이 한꺼번에
 * 낮아진다. 중앙값은 이상치에 흔들리지 않는다.
 *
 * <p><b>왜 해제 거래를 빼는가</b> — 해제된 계약은 거래가 아니다. 제공처는 신고 뒤 해제된 거래를 지우지 않고 해제 표시만
 * 달아 두므로, 빼지 않으면 성사되지 않은 가격이 중앙값에 섞인다.
 *
 * <p><b>표본이 없으면 값을 만들지 않는다.</b> 법정동 표본이 없으면 같은 유형 · 같은 면적대의 자치구 표본으로 한 단계 넓히고,
 * 그것도 없으면 빈 값을 돌려준다. 적재는 그 매물을 건너뛰고 기록한다 — 시세는 판정의 입력이라 지어낸 값을 넣으면 위험도가 조용히
 * 틀어진다.
 */
public final class MarketPriceCalculator {

    /** 유형 + 법정동 + 면적대 표본. */
    private final Map<DongAreaKey, Sample> byLegalDong;

    /** 유형 + 면적대로 묶은 자치구 단위 표본. 법정동 표본이 없을 때 한 단계 넓힌다. */
    private final Map<DistrictAreaKey, Sample> byDistrict;

    private MarketPriceCalculator(Map<DongAreaKey, Sample> byLegalDong, Map<DistrictAreaKey, Sample> byDistrict) {
        this.byLegalDong = byLegalDong;
        this.byDistrict = byDistrict;
    }

    /**
     * 자치구 하나의 매매 실거래 목록으로 시세표를 만든다. 해제 거래와 필수 값(전용면적 · 거래금액 · 계약일)이 빈 거래는
     * 표본에 넣지 않는다.
     */
    public static MarketPriceCalculator from(List<SaleTransaction> transactions) {
        Map<DongAreaKey, Sample> byLegalDong = new HashMap<>();
        Map<DistrictAreaKey, Sample> byDistrict = new HashMap<>();

        for (SaleTransaction transaction : transactions) {
            if (!isSample(transaction)) {
                continue;
            }
            PropertyType propertyType = toPropertyType(transaction.buildingType());
            AreaBand areaBand = AreaBand.of(transaction.areaSqm());
            byLegalDong
                    .computeIfAbsent(new DongAreaKey(propertyType, transaction.legalDongName(), areaBand),
                            key -> new Sample())
                    .add(transaction);
            byDistrict
                    .computeIfAbsent(new DistrictAreaKey(propertyType, areaBand), key -> new Sample())
                    .add(transaction);
        }
        return new MarketPriceCalculator(byLegalDong, byDistrict);
    }

    /**
     * 매물 유형 · 법정동 · 전용면적에 해당하는 시세를 찾는다. 표본이 없으면 빈 값. 넓힐 때도 다른 유형의 표본은 쓰지 않는다.
     */
    public Optional<MarketPrice> find(PropertyType propertyType, String legalDongName, BigDecimal areaSqm) {
        AreaBand areaBand = AreaBand.of(areaSqm);
        Sample sample = byLegalDong.get(new DongAreaKey(propertyType, legalDongName, areaBand));
        if (sample == null) {
            sample = byDistrict.get(new DistrictAreaKey(propertyType, areaBand));
        }
        return Optional.ofNullable(sample).map(Sample::toMarketPrice);
    }

    /**
     * 표본에 넣을 거래인가. 필수 값이 빈 거래는 면적대 분류 · 중앙값 계산에서 예외가 나므로 여기서 거른다 — 레코드는 제공처가
     * 비워 보내는 값을 막지 않는다.
     */
    private static boolean isSample(SaleTransaction transaction) {
        return !transaction.cancelled()
                && transaction.areaSqm() != null
                && transaction.dealAmount() != null
                && transaction.contractDate() != null;
    }

    /**
     * 매매 서비스 구분을 매물 유형으로 옮긴다. 외부의 구분이 시세표의 키로 새지 않게 하고, 서비스 구분이 늘면 여기서 컴파일이
     * 막혀 짝을 맞추게 한다.
     */
    private static PropertyType toPropertyType(SaleBuildingType buildingType) {
        return switch (buildingType) {
            case APARTMENT -> PropertyType.APARTMENT;
            case OFFICETEL -> PropertyType.OFFICETEL;
        };
    }

    /**
     * 산출된 시세.
     *
     * @param amount   중앙값(원)
     * @param baseDate 기준일. 표본에서 가장 최근 계약일이다. 화면에 시세 기준일로 표시한다
     */
    public record MarketPrice(Long amount, LocalDate baseDate) {
    }

    private record DongAreaKey(PropertyType propertyType, String legalDongName, AreaBand areaBand) {
    }

    private record DistrictAreaKey(PropertyType propertyType, AreaBand areaBand) {
    }

    /** 한 묶음의 표본. 거래금액 목록과 가장 최근 계약일을 들고 있다. */
    private static final class Sample {
        private final List<Long> amounts = new ArrayList<>();
        private LocalDate latestContractDate;

        private void add(SaleTransaction transaction) {
            amounts.add(transaction.dealAmount());
            if (latestContractDate == null || transaction.contractDate().isAfter(latestContractDate)) {
                latestContractDate = transaction.contractDate();
            }
        }

        private MarketPrice toMarketPrice() {
            return new MarketPrice(median(), latestContractDate);
        }

        /** 짝수 개면 가운데 두 값의 평균이다. 원 단위 정수라 나머지는 버린다. */
        private Long median() {
            List<Long> sorted = amounts.stream().sorted(Comparator.naturalOrder()).toList();
            int size = sorted.size();
            int middle = size / 2;
            if (size % 2 == 1) {
                return sorted.get(middle);
            }
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        }
    }
}
