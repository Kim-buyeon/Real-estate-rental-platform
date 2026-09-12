package com.duri.rentalplatform.domain.property.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PropertyNaturalKey} compact 생성자의 면적 scale 정규화 검증.
 *
 * <p>근거 — 데이터 적재 설계서 1.4 「동일 매물의 중복 적재는 자연키로 차단한다. 재실행해도 결과가
 * 같아야 한다」와 {@code property.area_sqm} 의 컬럼 정의 {@code NUMERIC(7,2)}. 적재기가 만드는 방금
 * 계산한 키(예: 실거래 응답의 {@code 84.9})와, 이미 저장된 매물을 DB에서 다시 읽어 만든 키(컬럼
 * 정의상 항상 {@code 84.90})가 scale 이 달라 다른 {@link BigDecimal} 로 남으면 {@code equals} 가
 * 어긋나 같은 매물을 다른 매물로 오인해 중복 적재를 차단하지 못한다.
 */
class PropertyNaturalKeyTest {

    private static final String ADDRESS = "서울특별시 강남구 역삼동 123-45";
    private static final Integer FLOOR = 3;
    private static final Long DEPOSIT = 300_000_000L;
    private static final Long MONTHLY_RENT = 0L;

    @Test
    @DisplayName("scale이 1인 84.9와 scale이 2인 84.90은 같은 자연키다")
    void treatsDifferentAreaScalesRepresentingTheSameValueAsEqual() {
        PropertyNaturalKey scaleOne =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.9"), FLOOR, DEPOSIT, MONTHLY_RENT);
        PropertyNaturalKey scaleTwo =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.90"), FLOOR, DEPOSIT, MONTHLY_RENT);

        assertThat(scaleOne).isEqualTo(scaleTwo);
        assertThat(scaleOne.hashCode()).isEqualTo(scaleTwo.hashCode());
    }

    @Test
    @DisplayName("compact 생성자는 areaSqm을 항상 scale 2로 고정한다")
    void normalizesAreaScaleToTwoRegardlessOfInputScale() {
        PropertyNaturalKey noDecimal =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84"), FLOOR, DEPOSIT, MONTHLY_RENT);
        PropertyNaturalKey threeDecimalsSameValue =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.900"), FLOOR, DEPOSIT, MONTHLY_RENT);
        PropertyNaturalKey twoDecimalsSameValue =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.90"), FLOOR, DEPOSIT, MONTHLY_RENT);

        assertThat(noDecimal.areaSqm().scale()).isEqualTo(2);
        assertThat(threeDecimalsSameValue.areaSqm().scale()).isEqualTo(2);
        // 84.900과 84.90은 같은 값이므로 scale만 다르던 입력도 같은 자연키가 된다.
        assertThat(threeDecimalsSameValue).isEqualTo(twoDecimalsSameValue);
    }

    @Test
    @DisplayName("소수 셋째 자리가 달라 반올림 결과가 달라지면 다른 자연키다")
    void differsWhenRoundedValuesDiffer() {
        PropertyNaturalKey roundsDown =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.901"), FLOOR, DEPOSIT, MONTHLY_RENT);
        PropertyNaturalKey roundsUp =
                new PropertyNaturalKey(ADDRESS, new BigDecimal("84.905"), FLOOR, DEPOSIT, MONTHLY_RENT);

        // HALF_UP이므로 84.901 -> 84.90, 84.905 -> 84.91 이 되어 서로 다른 키다.
        assertThat(roundsDown).isNotEqualTo(roundsUp);
    }
}
