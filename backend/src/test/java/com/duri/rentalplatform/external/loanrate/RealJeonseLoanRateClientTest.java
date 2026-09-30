package com.duri.rentalplatform.external.loanrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealJeonseLoanRateClient} — 요청 조립과 응답 해석. 외부로 나가지 않고 {@link MockRestServiceServer} 로 응답을 흉내 낸다.
 * {@code @Retry} · {@code @CircuitBreaker} 는 프록시가 없어 걸리지 않는다.
 *
 * <p>응답 본문의 값은 제공처 문서 출력결과 표의 샘플(가은행 · 2.85 · 2.83 · 348 · 30321500000)이다.
 */
class RealJeonseLoanRateClientTest {

    private static final String BASE_URL = "https://apis.data.go.kr/B551408";

    /** 포털이 발급하는 인코딩 키의 형태. {@code %} 가 들어 있다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    private static final LoanRateQuery OFFICETEL_2026_08 =
            new LoanRateQuery(YearMonth.of(2026, 8), LoanRateHouseType.OFFICETEL);

    private static final String EXPECTED_URI = BASE_URL
            + "/rent-loan-rate-multi-dimensional-info/dimensional-list"
            + "?serviceKey=" + ENCODED_KEY
            + "&pageNo=1&numOfRows=100&loanYm=202608&houseTycd=10&dataType=JSON";

    @Test
    @DisplayName("인코딩 키를 그대로 · 기준월을 YYYYMM · 오피스텔을 10 으로 보내고, 금리는 가중평균(avgLoanRat2)을 읽는다")
    void sendsQueryAndReadsWeightedAverage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI)).andRespond(withSuccess("""
                {"header":{"resultCode":"00","resultMsg":"정상"},"body":{"pageNo":1,"totalCount":1,"numOfRows":100,
                 "items":[{"avgLoanRat":"2.85","minLoanRat":"2.7","cnt":348,"loanAmt":"30321500000",
                           "maxLoanRat":"3.89","avgLoanRat2":"2.83","bankNm":"가은행"}]}}
                """, MediaType.APPLICATION_JSON));

        List<BankLoanRate> rates = clientOf(builder).findBankLoanRates(OFFICETEL_2026_08);

        server.verify();
        assertThat(rates).containsExactly(new BankLoanRate("가은행", new BigDecimal("2.83"), 30_321_500_000L));
    }

    @Test
    @DisplayName("은행명 · 가중평균 금리 · 실행금액 중 빠진 은행과 금리 0 인 은행만 버린다")
    void skipsIncompleteBanks() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI)).andRespond(withSuccess("""
                {"header":{"resultCode":"00"},"body":{"totalCount":4,"items":[
                 {"avgLoanRat2":"2.83","loanAmt":"100","bankNm":"가은행"},
                 {"avgLoanRat2":"","loanAmt":"100","bankNm":"나은행"},
                 {"avgLoanRat2":"0","loanAmt":"100","bankNm":"다은행"},
                 {"avgLoanRat2":"3.1","loanAmt":"100","bankNm":" "}]}}
                """, MediaType.APPLICATION_JSON));

        List<BankLoanRate> rates = clientOf(builder).findBankLoanRates(OFFICETEL_2026_08);

        assertThat(rates).extracting(BankLoanRate::bankName).containsExactly("가은행");
    }

    @Test
    @DisplayName("그 달 실적이 없으면(items 가 빈 문자열) 예외가 아니라 빈 목록이다")
    void emptyItemsIsEmptyList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI)).andRespond(withSuccess("""
                {"header":{"resultCode":"00"},"body":{"totalCount":0,"items":""}}
                """, MediaType.APPLICATION_JSON));

        assertThat(clientOf(builder).findBankLoanRates(OFFICETEL_2026_08)).isEmpty();
    }

    @Test
    @DisplayName("HTTP 200 이어도 resultCode 가 00 이 아니면 EXTERNAL_API_UNAVAILABLE")
    void errorResultCodeThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI)).andRespond(withSuccess("""
                {"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}
                """, MediaType.APPLICATION_JSON));

        assertUnavailable(() -> clientOf(builder).findBankLoanRates(OFFICETEL_2026_08));
    }

    @Test
    @DisplayName("JSON 요청에 XML 오류가 와도(인증 오류 등) 파싱 실패를 EXTERNAL_API_UNAVAILABLE 로 바꾼다")
    void xmlErrorBodyThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(EXPECTED_URI)).andRespond(withSuccess("""
                <OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>
                """, MediaType.APPLICATION_XML));

        assertUnavailable(() -> clientOf(builder).findBankLoanRates(OFFICETEL_2026_08));
    }

    @Test
    @DisplayName("디코딩 키나 빈 키면 생성에서 실패한다 — 설정 오류가 연동 실패로 가려지지 않게")
    void rejectsDecodedOrBlankKey() {
        assertThatThrownBy(() -> clientOf(RestClient.builder(), "abc+def/ghi=="))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clientOf(RestClient.builder(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    private RealJeonseLoanRateClient clientOf(RestClient.Builder builder) {
        return clientOf(builder, ENCODED_KEY);
    }

    private RealJeonseLoanRateClient clientOf(RestClient.Builder builder, String apiKey) {
        ExternalApiProperties.ClientSettings settings =
                new ExternalApiProperties.ClientSettings("real", BASE_URL, apiKey, null, null, null);
        return new RealJeonseLoanRateClient(
                builder.build(), new ExternalApiProperties(null, null, null, null, null, settings));
    }
}
