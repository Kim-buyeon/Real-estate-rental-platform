package com.duri.rentalplatform.domain.property.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link AreaBand#of(BigDecimal)} 경계값 검증.
 *
 * <p>기대값 표 — 경계는 하한 포함 · 상한 미포함이다(클래스 Javadoc). 경계 40 / 60 / 85 / 135㎡ 각각의
 * 바로 아래 값 · 경계값 자체 · 바로 위 값을 넣어 어느 구간으로 갈리는지 확인한다. 이 값이 깡통전세 판정(RISK-02)
 * 기준금액의 밑값이자 전세가율의 분모인 시세 산출 표본을 묶는 단위라 경계가 한 칸만 밀려도 표본이 섞인다.
 */
class AreaBandTest {

    static Stream<Arguments> boundaryCases() {
        return Stream.of(
                // 40㎡ 경계
                Arguments.of("39.99", AreaBand.UNDER_40),
                Arguments.of("40.00", AreaBand.FROM_40_TO_60),
                Arguments.of("40.01", AreaBand.FROM_40_TO_60),
                // 60㎡ 경계
                Arguments.of("59.99", AreaBand.FROM_40_TO_60),
                Arguments.of("60.00", AreaBand.FROM_60_TO_85),
                Arguments.of("60.01", AreaBand.FROM_60_TO_85),
                // 85㎡ 경계
                Arguments.of("84.99", AreaBand.FROM_60_TO_85),
                Arguments.of("85.00", AreaBand.FROM_85_TO_135),
                Arguments.of("85.01", AreaBand.FROM_85_TO_135),
                // 135㎡ 경계
                Arguments.of("134.99", AreaBand.FROM_85_TO_135),
                Arguments.of("135.00", AreaBand.OVER_135),
                Arguments.of("135.01", AreaBand.OVER_135),
                // 극단값
                Arguments.of("0.01", AreaBand.UNDER_40),
                Arguments.of("999.99", AreaBand.OVER_135)
        );
    }

    @ParameterizedTest(name = "{0}㎡ → {1}")
    @MethodSource("boundaryCases")
    void classifiesAreaIntoExpectedBand(String areaSqm, AreaBand expected) {
        assertThat(AreaBand.of(new BigDecimal(areaSqm))).isEqualTo(expected);
    }
}
