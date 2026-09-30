package com.duri.rentalplatform.domain.property.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.calculator.MarketPriceCalculator.MarketPrice;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.external.realestate.SaleBuildingType;
import com.duri.rentalplatform.external.realestate.SaleTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketPriceCalculator} 시세(같은 유형 매매 실거래가 중앙값) 산출 검증. 따로 적지 않은 행은 전부 아파트 매매 · 아파트 매물이다.
 *
 * <p>이 값은 깡통전세 판정(RISK-02) 기준금액의 밑값이자 전세가율의 분모(주택가액)다. 기대값 표는 클래스 Javadoc이 근거다.
 * 면적대 경계는 {@code AreaBand} 가 갖고 여기서는 한 구간 안쪽 값(55㎡ → 40~60㎡)과 다른 구간 값(100㎡ → 85~135㎡)만 쓴다.
 *
 * <table border="1">
 *   <caption>기대값 표</caption>
 *   <tr><th>상황</th><th>표본</th><th>기대 시세</th></tr>
 *   <tr><td>같은 법정동 · 같은 면적대, 홀수 표본</td><td>5억, 6억, 7억</td><td>6억(가운데 값)</td></tr>
 *   <tr><td>같은 법정동 · 같은 면적대, 짝수 표본</td><td>5억, 6억, 7억, 8억</td><td>6억5천(가운데 두 값 평균)</td></tr>
 *   <tr><td>기준일</td><td>1월 1일 · 3월 15일 계약</td><td>3월 15일(가장 최근 계약일)</td></tr>
 *   <tr><td>해제 거래가 섞임</td><td>5억 + 해제 1억</td><td>5억 — 해제 거래 제외</td></tr>
 *   <tr><td>해제 거래가 가장 최근</td><td>1월 5억 + 해제 3월</td><td>기준일 1월 — 해제 거래는 기준일에도 쓰지 않는다</td></tr>
 *   <tr><td>법정동 표본 없음, 같은 면적대의 자치구 표본 있음</td><td>다른 동의 같은 면적대 4억 · 6억</td><td>5억(자치구 표본의 중앙값)</td></tr>
 *   <tr><td>법정동 · 자치구 어디에도 같은 면적대 표본 없음</td><td>다른 면적대만</td><td>빈 값</td></tr>
 *   <tr><td>해제 거래만 있음</td><td>해제만</td><td>빈 값</td></tr>
 *   <tr><td>필수 값이 빈 거래</td><td>거래금액 없음 + 5억</td><td>5억 — 빈 거래는 표본에서 빠지고 예외가 나지 않는다</td></tr>
 *   <tr><td>같은 동 · 같은 면적대에 두 유형이 있음</td><td>아파트 5억 · 7억, 오피스텔 2억 · 3억</td><td>아파트 매물 6억, 오피스텔 매물 2억5천 — 각자 자기 유형의 중앙값</td></tr>
 *   <tr><td>법정동에 자기 유형 표본 없음, 같은 동에 다른 유형만 있음</td><td>역삼동 오피스텔 2억 + 논현동 아파트 6억</td><td>아파트 매물 6억 — 다른 유형이 아니라 같은 유형의 자치구 표본으로 넓힌다</td></tr>
 *   <tr><td>자치구 어디에도 자기 유형 표본 없음, 다른 유형만 있음</td><td>오피스텔 2억만</td><td>아파트 매물 빈 값 — 넓힐 때도 유형을 섞지 않는다</td></tr>
 * </table>
 */
class MarketPriceCalculatorTest {

    private static final String LEGAL_DONG = "역삼동";
    private static final String OTHER_LEGAL_DONG = "논현동";
    private static final BigDecimal AREA_IN_BAND = new BigDecimal("55.00"); // FROM_40_TO_60
    private static final BigDecimal AREA_OTHER_BAND = new BigDecimal("100.00"); // FROM_85_TO_135

    @Nested
    @DisplayName("같은 법정동 · 같은 면적대 표본이 있을 때")
    class WhenDongSampleExists {

        @Test
        @DisplayName("표본이 홀수 개면 가운데 거래금액이 시세다")
        void medianOfOddSampleIsTheMiddleValue() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 700_000_000L, LocalDate.of(2026, 3, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 600_000_000L, LocalDate.of(2026, 2, 1))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(600_000_000L);
        }

        @Test
        @DisplayName("표본이 짝수 개면 가운데 두 거래금액의 평균이 시세다")
        void medianOfEvenSampleIsTheAverageOfTwoMiddleValues() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 600_000_000L, LocalDate.of(2026, 2, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 700_000_000L, LocalDate.of(2026, 3, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 800_000_000L, LocalDate.of(2026, 4, 1))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            // (6억 + 7억) / 2 = 6억5천
            assertThat(price.get().amount()).isEqualTo(650_000_000L);
        }

        @Test
        @DisplayName("기준일은 표본 중 가장 최근 계약일이다")
        void baseDateIsTheLatestContractDateInTheSample() {
            LocalDate latest = LocalDate.of(2026, 3, 15);
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 700_000_000L, latest)
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().baseDate()).isEqualTo(latest);
        }

        @Test
        @DisplayName("해제 거래는 표본에서 제외된다")
        void excludesCancelledDealsFromTheSample() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    cancelledSale(LEGAL_DONG, AREA_IN_BAND, 100_000_000L, LocalDate.of(2026, 2, 1))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            // 해제 거래(1억)가 섞였다면 짝수 표본이 되어 (1억 + 5억) / 2 = 3억이 나온다. 5억 1건만 표본이다.
            assertThat(price.get().amount()).isEqualTo(500_000_000L);
        }

        @Test
        @DisplayName("해제 거래의 계약일은 기준일에도 쓰지 않는다")
        void cancelledDealDoesNotMoveTheBaseDate() {
            LocalDate validDate = LocalDate.of(2026, 1, 1);
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, validDate),
                    cancelledSale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 3, 1))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().baseDate()).isEqualTo(validDate);
        }

        @Test
        @DisplayName("필수 값(거래금액)이 빈 거래는 예외 없이 표본에서 빠진다")
        void skipsATransactionMissingDealAmount() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    new SaleTransaction("11680", LEGAL_DONG, "테스트아파트", "100-2", AREA_IN_BAND, 3, null,
                            LocalDate.of(2026, 2, 1), 2010, false, SaleBuildingType.APARTMENT, "TEST_FIXTURE")
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(500_000_000L);
        }
    }

    @Nested
    @DisplayName("법정동 표본이 없을 때")
    class WhenDongSampleIsMissing {

        @Test
        @DisplayName("같은 면적대의 자치구 표본으로 한 단계 넓힌다")
        void widensToDistrictSampleOfTheSameAreaBand() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(OTHER_LEGAL_DONG, AREA_IN_BAND, 400_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(OTHER_LEGAL_DONG, AREA_IN_BAND, 600_000_000L, LocalDate.of(2026, 2, 1)),
                    // 같은 법정동이지만 다른 면적대 — 넓힐 때 섞이지 않는다.
                    sale(LEGAL_DONG, AREA_OTHER_BAND, 1_500_000_000L, LocalDate.of(2026, 3, 1))
            ));

            // LEGAL_DONG 의 40~60㎡ 표본은 없고 OTHER_LEGAL_DONG 에만 있다.
            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(500_000_000L);
            assertThat(price.get().baseDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        }

        @Test
        @DisplayName("자치구 표본도 없으면 빈 값을 돌려주고 값을 지어내지 않는다")
        void returnsEmptyWhenNeitherDongNorDistrictSampleExists() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(OTHER_LEGAL_DONG, AREA_OTHER_BAND, 1_500_000_000L, LocalDate.of(2026, 1, 1))
            ));

            // 요청한 면적대(40~60㎡)와 다른 면적대(85~135㎡)의 표본만 있다.
            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isEmpty();
        }

        @Test
        @DisplayName("해제 거래만 있으면 자치구 표본도 없어 빈 값이다")
        void returnsEmptyWhenOnlyCancelledDealsExist() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    cancelledSale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    cancelledSale(OTHER_LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 2))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isEmpty();
        }
    }

    @Nested
    @DisplayName("매물 유형이 둘일 때")
    class WhenTwoPropertyTypesExist {

        @Test
        @DisplayName("같은 동 · 같은 면적대에 두 유형이 있으면 각자 자기 유형의 중앙값이 시세다")
        void eachTypeUsesItsOwnMedian() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    sale(LEGAL_DONG, AREA_IN_BAND, 500_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(LEGAL_DONG, AREA_IN_BAND, 700_000_000L, LocalDate.of(2026, 2, 1)),
                    officetelSale(LEGAL_DONG, AREA_IN_BAND, 200_000_000L, LocalDate.of(2026, 3, 1)),
                    officetelSale(LEGAL_DONG, AREA_IN_BAND, 300_000_000L, LocalDate.of(2026, 4, 1))
            ));

            Optional<MarketPrice> apartment = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);
            Optional<MarketPrice> officetel = calculator.find(PropertyType.OFFICETEL, LEGAL_DONG, AREA_IN_BAND);

            // 섞였다면 네 건의 중앙값 (3억 + 5억) / 2 = 4억이 둘 다에 나온다.
            assertThat(apartment).isPresent();
            assertThat(apartment.get().amount()).isEqualTo(600_000_000L);
            assertThat(apartment.get().baseDate()).isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(officetel).isPresent();
            assertThat(officetel.get().amount()).isEqualTo(250_000_000L);
            assertThat(officetel.get().baseDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        @DisplayName("법정동에 다른 유형만 있으면 그것을 쓰지 않고 같은 유형의 자치구 표본으로 넓힌다")
        void widensWithinTheSameTypeInsteadOfUsingAnotherTypeInTheDong() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    officetelSale(LEGAL_DONG, AREA_IN_BAND, 200_000_000L, LocalDate.of(2026, 1, 1)),
                    sale(OTHER_LEGAL_DONG, AREA_IN_BAND, 600_000_000L, LocalDate.of(2026, 2, 1))
            ));

            Optional<MarketPrice> price = calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(600_000_000L);
        }

        @Test
        @DisplayName("자치구 어디에도 같은 유형 표본이 없으면 다른 유형이 있어도 빈 값이다")
        void doesNotWidenAcrossTypes() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    officetelSale(LEGAL_DONG, AREA_IN_BAND, 200_000_000L, LocalDate.of(2026, 1, 1)),
                    officetelSale(OTHER_LEGAL_DONG, AREA_IN_BAND, 250_000_000L, LocalDate.of(2026, 1, 2))
            ));

            assertThat(calculator.find(PropertyType.APARTMENT, LEGAL_DONG, AREA_IN_BAND)).isEmpty();
            assertThat(calculator.find(PropertyType.OFFICETEL, LEGAL_DONG, AREA_IN_BAND)).isPresent();
        }
    }

    private SaleTransaction sale(String legalDongName, BigDecimal areaSqm, long dealAmount, LocalDate contractDate) {
        return transaction(legalDongName, areaSqm, dealAmount, contractDate, false, SaleBuildingType.APARTMENT);
    }

    private SaleTransaction officetelSale(
            String legalDongName, BigDecimal areaSqm, long dealAmount, LocalDate contractDate) {
        return transaction(legalDongName, areaSqm, dealAmount, contractDate, false, SaleBuildingType.OFFICETEL);
    }

    private SaleTransaction cancelledSale(
            String legalDongName, BigDecimal areaSqm, long dealAmount, LocalDate contractDate) {
        return transaction(legalDongName, areaSqm, dealAmount, contractDate, true, SaleBuildingType.APARTMENT);
    }

    private SaleTransaction transaction(String legalDongName, BigDecimal areaSqm, long dealAmount,
            LocalDate contractDate, boolean cancelled, SaleBuildingType buildingType) {
        return new SaleTransaction(
                "11680",
                legalDongName,
                "테스트아파트",
                "100-1",
                areaSqm,
                5,
                dealAmount,
                contractDate,
                2010,
                cancelled,
                buildingType,
                "TEST_FIXTURE");
    }
}
