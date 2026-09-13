package com.duri.rentalplatform.domain.property.enums;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전용면적 구간. 시세(중앙값) 산출 시 같은 규모끼리 묶는 단위다.
 *
 * <p>구간을 나누는 이유는 하나다. 같은 법정동이라도 30㎡ 원룸과 110㎡ 아파트의 보증금을 한 표본에 섞으면
 * 중앙값이 어느 매물의 시세도 아니게 된다. 이 값이 깡통전세 판정(RISK-02) 기준금액의 밑값이자 전세가율의 분모이므로 표본을 섞으면 판정이
 * 통째로 어긋난다.
 *
 * <p><b>경계값의 근거</b> — 문서에 정해진 것이 없어 국내 주택 통계가 공통으로 쓰는 경계를 따랐다.
 * 40㎡(초소형 · 도시형생활주택), 60㎡(주택법 시행령상 소형), 85㎡(주택법 국민주택규모),
 * 135㎡(대형 구분). 한국부동산원 · KOSIS 의 규모별 통계 구간과 같은 자리다.
 * <b>이 경계는 아직 어느 설계 문서에도 없다 — 미확정으로 두고 문서에 확정되면 그 값을 따른다.</b>
 * 경계는 하한 포함 · 상한 미포함이다(40㎡ 는 {@link #FROM_40_TO_60}).
 */
@Getter
@RequiredArgsConstructor
public enum AreaBand {
    UNDER_40("40㎡ 미만", null, new BigDecimal("40")),
    FROM_40_TO_60("40~60㎡", new BigDecimal("40"), new BigDecimal("60")),
    FROM_60_TO_85("60~85㎡", new BigDecimal("60"), new BigDecimal("85")),
    FROM_85_TO_135("85~135㎡", new BigDecimal("85"), new BigDecimal("135")),
    OVER_135("135㎡ 이상", new BigDecimal("135"), null);

    private final String label;
    private final BigDecimal lowerInclusive;
    private final BigDecimal upperExclusive;

    /**
     * 전용면적이 속한 구간을 찾는다.
     */
    public static AreaBand of(BigDecimal areaSqm) {
        for (AreaBand band : values()) {
            boolean aboveLower = band.lowerInclusive == null || areaSqm.compareTo(band.lowerInclusive) >= 0;
            boolean belowUpper = band.upperExclusive == null || areaSqm.compareTo(band.upperExclusive) < 0;
            if (aboveLower && belowUpper) {
                return band;
            }
        }
        // 위 분기가 전 구간을 덮으므로 도달하지 않는다. 구간을 고치다 빈틈이 생기면 여기서 드러난다.
        throw new IllegalArgumentException("면적 구간을 찾을 수 없다: " + areaSqm);
    }
}
