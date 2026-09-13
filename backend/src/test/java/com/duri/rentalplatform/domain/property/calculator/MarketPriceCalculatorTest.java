package com.duri.rentalplatform.domain.property.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.property.calculator.MarketPriceCalculator.MarketPrice;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketPriceCalculator} 시세(중앙값) 산출 검증.
 *
 * <p>이 값은 깡통전세 판정(RISK-02) 기준금액의 밑값이자 전세가율의 분모다. 기대값 표는 클래스 Javadoc이 근거다.
 *
 * <table border="1">
 *   <caption>기대값 표</caption>
 *   <tr><th>상황</th><th>표본</th><th>기대 시세</th></tr>
 *   <tr><td>같은 법정동 · 같은 면적대, 홀수 표본</td><td>1억, 2억, 3억</td><td>2억(가운데 값)</td></tr>
 *   <tr><td>같은 법정동 · 같은 면적대, 짝수 표본</td><td>1억, 2억, 3억, 4억</td><td>2억5천(가운데 두 값 평균)</td></tr>
 *   <tr><td>법정동 표본 없음, 같은 면적대의 자치구 표본 있음</td><td>다른 동의 같은 면적대</td><td>자치구 표본의 중앙값</td></tr>
 *   <tr><td>법정동 · 자치구 어디에도 표본 없음</td><td>없음</td><td>빈 값</td></tr>
 *   <tr><td>월세 계약만 있음</td><td>월세만</td><td>빈 값 — 표본에서 제외됨</td></tr>
 * </table>
 */
class MarketPriceCalculatorTest {

    private static final String LEGAL_DONG = "역삼동";
    private static final String OTHER_LEGAL_DONG = "논현동";
    private static final BigDecimal AREA_IN_BAND = new BigDecimal("55.00"); // FROM_40_TO_60

    @Nested
    @DisplayName("같은 법정동 · 같은 면적대 표본이 있을 때")
    class WhenDongSampleExists {

        @Test
        @DisplayName("표본이 홀수 개면 가운데 값이 시세다")
        void medianOfOddSampleIsTheMiddleValue() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 100_000_000L, LocalDate.of(2026, 1, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 200_000_000L, LocalDate.of(2026, 2, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 300_000_000L, LocalDate.of(2026, 3, 1))
            ));

            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(200_000_000L);
        }

        @Test
        @DisplayName("표본이 짝수 개면 가운데 두 값의 평균이 시세다")
        void medianOfEvenSampleIsTheAverageOfTwoMiddleValues() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 100_000_000L, LocalDate.of(2026, 1, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 200_000_000L, LocalDate.of(2026, 2, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 300_000_000L, LocalDate.of(2026, 3, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 400_000_000L, LocalDate.of(2026, 4, 1))
            ));

            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            // (2억 + 3억) / 2 = 2억5천
            assertThat(price.get().amount()).isEqualTo(250_000_000L);
        }

        @Test
        @DisplayName("기준일은 표본 중 가장 최근 계약일이다")
        void baseDateIsTheLatestContractDateInTheSample() {
            LocalDate latest = LocalDate.of(2026, 3, 15);
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 100_000_000L, LocalDate.of(2026, 1, 1)),
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 300_000_000L, latest)
            ));

            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().baseDate()).isEqualTo(latest);
        }

        @Test
        @DisplayName("월세 계약은 표본에서 제외된다")
        void excludesMonthlyRentTransactionsFromTheSample() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(LEGAL_DONG, AREA_IN_BAND, 100_000_000L, LocalDate.of(2026, 1, 1)),
                    monthlyRentTransaction(LEGAL_DONG, AREA_IN_BAND, 10_000_000L, 900_000L,
                            LocalDate.of(2026, 2, 1))
            ));

            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            // 월세 계약(1천만)이 섞였다면 중앙값이 흔들린다. 전세 1건만 표본이므로 그 값 그대로다.
            assertThat(price.get().amount()).isEqualTo(100_000_000L);
        }
    }

    @Nested
    @DisplayName("법정동 표본이 없을 때")
    class WhenDongSampleIsMissing {

        @Test
        @DisplayName("같은 면적대의 자치구 표본으로 한 단계 넓힌다")
        void widensToDistrictSampleOfTheSameAreaBand() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(OTHER_LEGAL_DONG, AREA_IN_BAND, 150_000_000L, LocalDate.of(2026, 1, 1)),
                    leaseTransaction(OTHER_LEGAL_DONG, AREA_IN_BAND, 250_000_000L, LocalDate.of(2026, 2, 1))
            ));

            // LEGAL_DONG에는 표본이 없고 OTHER_LEGAL_DONG에만 있다.
            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isPresent();
            assertThat(price.get().amount()).isEqualTo(200_000_000L);
        }

        @Test
        @DisplayName("자치구 표본도 없으면 빈 값을 돌려주고 값을 지어내지 않는다")
        void returnsEmptyWhenNeitherDongNorDistrictSampleExists() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    leaseTransaction(OTHER_LEGAL_DONG, new BigDecimal("100.00"), 500_000_000L,
                            LocalDate.of(2026, 1, 1))
            ));

            // 요청한 면적대(FROM_40_TO_60)와 다른 면적대(FROM_85_TO_135)의 표본만 있다.
            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isEmpty();
        }

        @Test
        @DisplayName("월세 계약만 있으면 자치구 표본도 없어 빈 값이다")
        void returnsEmptyWhenOnlyMonthlyRentTransactionsExist() {
            MarketPriceCalculator calculator = MarketPriceCalculator.from(List.of(
                    monthlyRentTransaction(LEGAL_DONG, AREA_IN_BAND, 10_000_000L, 900_000L,
                            LocalDate.of(2026, 1, 1))
            ));

            Optional<MarketPrice> price = calculator.find(LEGAL_DONG, AREA_IN_BAND);

            assertThat(price).isEmpty();
        }
    }

    private RentTransaction leaseTransaction(
            String legalDongName, BigDecimal areaSqm, long deposit, LocalDate contractDate) {
        return transaction(legalDongName, areaSqm, deposit, 0L, contractDate);
    }

    private RentTransaction monthlyRentTransaction(
            String legalDongName, BigDecimal areaSqm, long deposit, long monthlyRent, LocalDate contractDate) {
        return transaction(legalDongName, areaSqm, deposit, monthlyRent, contractDate);
    }

    private RentTransaction transaction(
            String legalDongName, BigDecimal areaSqm, long deposit, long monthlyRent, LocalDate contractDate) {
        return new RentTransaction(
                "11680",
                legalDongName,
                "테스트아파트",
                "100-1",
                areaSqm,
                5,
                deposit,
                monthlyRent,
                contractDate,
                2010,
                RentBuildingType.APARTMENT,
                "TEST_FIXTURE");
    }
}
