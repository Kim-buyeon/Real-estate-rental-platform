package com.duri.rentalplatform.external.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealRentTransactionClient} 검증. 외부로 나가지 않고 {@link MockRestServiceServer} 로 응답을 흉내 낸다.
 *
 * <p>{@code @Retry} · {@code @CircuitBreaker} 는 프록시가 없어 걸리지 않는다. 요청 조립과 응답 해석만 본다.
 */
class RealRentTransactionClientTest {

    private static final String BASE_URL = "https://apis.data.go.kr/1613000";

    /** 포털이 발급하는 인코딩 키의 형태. {@code %} 가 들어 있다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    private static final RentTransactionQuery QUERY =
            new RentTransactionQuery("11680", YearMonth.of(2025, 8), RentBuildingType.APARTMENT);

    private static final String EXPECTED_URI = BASE_URL
            + "/RTMSDataSvcAptRent/getRTMSDataSvcAptRent"
            + "?serviceKey=" + ENCODED_KEY
            + "&LAWD_CD=11680&DEAL_YMD=202508&pageNo=1&numOfRows=1000";

    @Test
    @DisplayName("인코딩 키를 다시 인코딩하지 않고 그대로 보내고, 만원 단위 금액을 원 단위로 옮긴다")
    void sendsEncodedKeyAsIsAndParsesItems() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI))
                .andRespond(withSuccess(okBody(), MediaType.APPLICATION_XML));

        List<RentTransaction> transactions = clientOf(builder).findRentTransactions(QUERY);

        server.verify();
        assertThat(transactions).hasSize(1);
        RentTransaction transaction = transactions.getFirst();
        assertThat(transaction.buildingName()).isEqualTo("까치마을");
        assertThat(transaction.areaSqm()).isEqualByComparingTo(new BigDecimal("34.44"));
        assertThat(transaction.deposit()).isEqualTo(330_000_000L);
        assertThat(transaction.monthlyRent()).isZero();
        assertThat(transaction.contractDate()).isEqualTo(LocalDate.of(2025, 8, 27));
    }

    @Test
    @DisplayName("HTTP 200 이어도 resultCode 가 오류면 거래 0건이 아니라 BusinessException 을 던진다")
    void errorResultCodeThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI))
                .andRespond(withSuccess("""
                        <response><header><resultCode>30</resultCode><resultMsg>ERROR</resultMsg></header></response>
                        """, MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> clientOf(builder).findRentTransactions(QUERY))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("디코딩 키나 빈 키면 요청 전에 생성에서 실패한다 — 설정 오류가 연동실패로 기록되지 않게")
    void rejectsDecodedOrBlankKeyAtConstruction() {
        assertThatThrownBy(() -> clientOf(RestClient.builder(), "abc+def/ghi=="))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clientOf(RestClient.builder(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    private RealRentTransactionClient clientOf(RestClient.Builder builder) {
        return clientOf(builder, ENCODED_KEY);
    }

    private RealRentTransactionClient clientOf(RestClient.Builder builder, String apiKey) {
        ExternalApiProperties.ClientSettings settings =
                new ExternalApiProperties.ClientSettings("real", BASE_URL, apiKey, null, null, null);
        return new RealRentTransactionClient(
                builder.build(), new ExternalApiProperties(settings, settings, settings));
    }

    private String okBody() {
        // Content-Type 에 charset 을 붙이지 않는다 — 그래도 한글이 깨지지 않는지 함께 본다.
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>
                <body><items><item>
                <aptNm>까치마을</aptNm><buildYear>1993</buildYear>
                <dealDay>27</dealDay><dealMonth>8</dealMonth><dealYear>2025</dealYear>
                <deposit>33,000</deposit><excluUseAr>34.44</excluUseAr><floor>10</floor>
                <jibun>746</jibun><monthlyRent>0</monthlyRent><umdNm>수서동</umdNm>
                </item></items><numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body>
                </response>
                """;
    }
}
