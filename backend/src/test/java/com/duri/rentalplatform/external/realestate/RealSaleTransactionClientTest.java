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
 * {@link RealSaleTransactionClient} 검증. 외부로 나가지 않고 {@link MockRestServiceServer} 로 응답을 흉내 낸다.
 *
 * <p>응답 형태(필드 이름 · 공백 한 칸으로 오는 빈 값 · 만원 단위 콤마 금액)는 2026-09-30 실호출 응답을 따랐고, 값은 지어낸
 * 것이다. {@code @Retry} · {@code @CircuitBreaker} 는 프록시가 없어 걸리지 않는다. 요청 조립과 응답 해석만 본다.
 */
class RealSaleTransactionClientTest {

    private static final String BASE_URL = "https://apis.data.go.kr/1613000";

    /** 포털이 발급하는 인코딩 키의 형태. {@code %} 가 들어 있다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    private static final SaleTransactionQuery APARTMENT_QUERY =
            new SaleTransactionQuery("11110", YearMonth.of(2026, 8), SaleBuildingType.APARTMENT);

    private static final SaleTransactionQuery OFFICETEL_QUERY =
            new SaleTransactionQuery("11110", YearMonth.of(2026, 8), SaleBuildingType.OFFICETEL);

    private static String uriOf(String servicePath, String operation, int pageNo) {
        return BASE_URL + "/" + servicePath + "/" + operation
                + "?serviceKey=" + ENCODED_KEY
                + "&LAWD_CD=11110&DEAL_YMD=202608&pageNo=" + pageNo + "&numOfRows=1000";
    }

    @Test
    @DisplayName("아파트 매매 상세: 인코딩 키를 다시 인코딩하지 않고 보내고, 만원 단위 콤마 금액을 원 단위로 옮긴다")
    void parsesApartmentTradeItem() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess(body(1, apartmentItem("시험아파트", "172,500", " ")), MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(APARTMENT_QUERY);

        server.verify();
        assertThat(transactions).hasSize(1);
        SaleTransaction transaction = transactions.getFirst();
        assertThat(transaction.lawdCode()).isEqualTo("11110");
        assertThat(transaction.legalDongName()).isEqualTo("시험동");
        assertThat(transaction.buildingName()).isEqualTo("시험아파트");
        assertThat(transaction.jibun()).isEqualTo("60");
        assertThat(transaction.areaSqm()).isEqualByComparingTo(new BigDecimal("84.858"));
        assertThat(transaction.floor()).isEqualTo(13);
        assertThat(transaction.dealAmount()).isEqualTo(1_725_000_000L);
        assertThat(transaction.contractDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(transaction.buildYear()).isEqualTo(2008);
        // 해제되지 않은 거래는 cdealType 이 공백 한 칸으로 온다.
        assertThat(transaction.cancelled()).isFalse();
        assertThat(transaction.buildingType()).isEqualTo(SaleBuildingType.APARTMENT);
        assertThat(transaction.dataSource()).isEqualTo("MOLIT_RTMS_APT_TRADE");
    }

    @Test
    @DisplayName("cdealType 에 값이 있으면 해제 거래로 표시해 돌려준다 — 버리지 않고 표본 제외는 시세 산출이 정한다")
    void marksCancelledDeal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess(body(1, apartmentItem("시험아파트", "90,000", "O")), MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(APARTMENT_QUERY);

        assertThat(transactions).singleElement()
                .satisfies(transaction -> assertThat(transaction.cancelled()).isTrue());
    }

    @Test
    @DisplayName("오피스텔 매매: offiNm 을 건물명으로 읽고 오피스텔 서비스 경로로 부른다")
    void parsesOfficetelTradeItem() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcOffiTrade", "getRTMSDataSvcOffiTrade", 1)))
                .andRespond(withSuccess(body(1, """
                        <item><buildYear>2004</buildYear><cdealDay> </cdealDay><cdealType> </cdealType>
                        <dealAmount>19,500</dealAmount><dealDay>28</dealDay><dealMonth>8</dealMonth><dealYear>2026</dealYear>
                        <excluUseAr>32.43</excluUseAr><floor>8</floor><jibun>71</jibun><offiNm>시험오피스텔</offiNm>
                        <sggCd>11110</sggCd><umdNm>시험동</umdNm></item>
                        """), MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(OFFICETEL_QUERY);

        server.verify();
        assertThat(transactions).singleElement().satisfies(transaction -> {
            assertThat(transaction.buildingName()).isEqualTo("시험오피스텔");
            assertThat(transaction.dealAmount()).isEqualTo(195_000_000L);
            assertThat(transaction.areaSqm()).isEqualByComparingTo(new BigDecimal("32.43"));
            assertThat(transaction.buildingType()).isEqualTo(SaleBuildingType.OFFICETEL);
            assertThat(transaction.dataSource()).isEqualTo("MOLIT_RTMS_OFFI_TRADE");
        });
    }

    @Test
    @DisplayName("거래금액이 빈 건은 그 건만 버리고 나머지는 돌려준다 — 버린 건도 받은 건수로 세어 다음 페이지를 부르지 않는다")
    void dropsOnlyTheItemMissingDealAmount() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess(body(2,
                        apartmentItem("시험아파트", "172,500", " ") + apartmentItem("빈금액아파트", " ", " ")),
                        MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(APARTMENT_QUERY);

        assertThat(transactions).extracting(SaleTransaction::buildingName).containsExactly("시험아파트");
    }

    @Test
    @DisplayName("totalCount 가 한 페이지보다 많으면 다음 페이지를 이어 받아 합친다")
    void collectsAllPages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess(body(2, apartmentItem("첫페이지아파트", "100,000", " ")),
                        MediaType.APPLICATION_XML));
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 2)))
                .andRespond(withSuccess(body(2, apartmentItem("둘째페이지아파트", "110,000", " ")),
                        MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(APARTMENT_QUERY);

        server.verify();
        assertThat(transactions).extracting(SaleTransaction::buildingName)
                .containsExactly("첫페이지아파트", "둘째페이지아파트");
    }

    @Test
    @DisplayName("한 페이지의 항목이 모두 버려져도 totalCount 에 닿기 전이면 다음 페이지를 받는다")
    void continuesPagingWhenAPageIsEntirelyDropped() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess(body(2, apartmentItem("빈금액아파트", " ", " ")),
                        MediaType.APPLICATION_XML));
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 2)))
                .andRespond(withSuccess(body(2, apartmentItem("둘째페이지아파트", "110,000", " ")),
                        MediaType.APPLICATION_XML));

        List<SaleTransaction> transactions = clientOf(builder).findSaleTransactions(APARTMENT_QUERY);

        server.verify();
        assertThat(transactions).extracting(SaleTransaction::buildingName).containsExactly("둘째페이지아파트");
    }

    @Test
    @DisplayName("HTTP 200 이어도 resultCode 가 오류면 거래 0건이 아니라 BusinessException 을 던진다")
    void errorResultCodeThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess("""
                        <response><header><resultCode>30</resultCode><resultMsg>ERROR</resultMsg></header></response>
                        """, MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> clientOf(builder).findSaleTransactions(APARTMENT_QUERY))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("DOCTYPE 이 든 응답은 파싱하지 않고 BusinessException 을 던진다 — XXE 차단")
    void rejectsDoctype() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf("RTMSDataSvcAptTradeDev", "getRTMSDataSvcAptTradeDev", 1)))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="utf-8"?>
                        <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
                        <response><header><resultCode>000</resultCode><resultMsg>&xxe;</resultMsg></header></response>
                        """, MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> clientOf(builder).findSaleTransactions(APARTMENT_QUERY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("디코딩 키나 빈 키면 요청 전에 생성에서 실패한다 — 설정 오류가 연동실패로 기록되지 않게")
    void rejectsDecodedOrBlankKeyAtConstruction() {
        assertThatThrownBy(() -> clientOf(RestClient.builder(), "abc+def/ghi=="))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clientOf(RestClient.builder(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    private RealSaleTransactionClient clientOf(RestClient.Builder builder) {
        return clientOf(builder, ENCODED_KEY);
    }

    private RealSaleTransactionClient clientOf(RestClient.Builder builder, String apiKey) {
        ExternalApiProperties.ClientSettings settings =
                new ExternalApiProperties.ClientSettings("real", BASE_URL, apiKey, null, null, null);
        return new RealSaleTransactionClient(
                builder.build(), new ExternalApiProperties(null, null, null, null, settings, null));
    }

    private static String apartmentItem(String aptNm, String dealAmount, String cdealType) {
        return """
                <item><aptDong> </aptDong><aptNm>%s</aptNm><bonbun>0060</bonbun><bubun>0000</bubun>
                <buildYear>2008</buildYear><cdealDay> </cdealDay><cdealType>%s</cdealType>
                <dealAmount>%s</dealAmount><dealDay>31</dealDay><dealMonth>8</dealMonth><dealYear>2026</dealYear>
                <excluUseAr>84.858</excluUseAr><floor>13</floor><jibun>60</jibun><sggCd>11110</sggCd>
                <umdCd>18700</umdCd><umdNm>시험동</umdNm></item>
                """.formatted(aptNm, cdealType, dealAmount);
    }

    /** Content-Type 에 charset 을 붙이지 않는다 — 그래도 한글이 깨지지 않는지 함께 본다. */
    private static String body(int totalCount, String items) {
        return """
                <?xml version="1.0" encoding="utf-8" standalone="yes"?>
                <response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>
                <body><items>%s</items><numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>%d</totalCount></body>
                </response>
                """.formatted(items, totalCount);
    }
}
