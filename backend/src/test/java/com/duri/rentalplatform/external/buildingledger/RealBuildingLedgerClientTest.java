package com.duri.rentalplatform.external.buildingledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link RealBuildingLedgerClient} 검증. 외부로 나가지 않고 {@link MockRestServiceServer} 로 응답을 흉내 낸다.
 *
 * <p>응답 형태(필드 이름 · JSON 숫자 면적 · 미기재 면적 0 · 공백 한 칸 빈 값 · 「번지」로 끝나는 대지위치 · 동마다 한 행)는
 * 2026-09-30 창신동 702 실호출 응답을 따랐고, 값은 지어낸 것이다. {@code @Retry} · {@code @CircuitBreaker} 는 프록시가 없어
 * 걸리지 않는다. 요청 조립 · 응답 해석 · 동 고르기만 본다.
 */
class RealBuildingLedgerClientTest {

    private static final String BASE_URL = "https://apis.data.go.kr/1613000/BldRgstHubService";

    /** 포털이 발급하는 인코딩 키의 형태. {@code %} 가 들어 있다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    private static final LedgerLookupKey KEY = new LedgerLookupKey("11110", "17400", "0702", "0000");
    private static final String PROPERTY_ADDRESS = "서울특별시 시험구 시험길 19 (시험동, 시험아파트)";

    private static String uriOf(int pageNo) {
        return BASE_URL + "/getBrTitleInfo?serviceKey=" + ENCODED_KEY
                + "&sigunguCd=11110&bjdongCd=17400&bun=0702&ji=0000&numOfRows=100&pageNo=" + pageNo + "&_type=json";
    }

    @Test
    @DisplayName("인코딩 키를 다시 인코딩하지 않고 보내고, 상가동을 건너 공동주택 주건축물의 표제부를 우리 형태로 옮긴다")
    void parsesTitleOfResidentialMainBuilding() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(2,
                "[" + item("상가건물", "0", "판매시설", "서울특별시 시험구 시험로17길 36 (시험동)", "3221.71", "19921125")
                        + "," + item("101동", "0", "공동주택", "서울특별시 시험구 시험길 19 (시험동)", "14544.66", "19921125")
                        + "]"), MediaType.APPLICATION_JSON));

        Optional<BuildingLedgerDocument> document = clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).hasValueSatisfying(ledger -> {
            // 끝의 「번지」를 떼어 적재가 주소 정규화에 넘기는 원문과 같은 꼴로 만든다.
            assertThat(ledger.ledgerAddress()).isEqualTo("서울특별시 시험구 시험동 702");
            assertThat(ledger.buildingPurpose()).isEqualTo("공동주택");
            assertThat(ledger.buildingStructure()).isEqualTo("철근콘크리트구조");
            // 건축면적 0 은 미기재다. 면적으로 옮기지 않는다.
            assertThat(ledger.buildingArea()).isNull();
            assertThat(ledger.totalFloorArea()).isEqualByComparingTo("14544.66");
            assertThat(ledger.totalFloorArea().scale()).isEqualTo(2);
            assertThat(ledger.approvalDate()).isEqualTo(LocalDate.of(1992, 11, 25));
            // 건축HUB 가 주지 않는 값은 확인하지 못함(null)이다.
            assertThat(ledger.violation()).isNull();
            assertThat(ledger.ownerName()).isNull();
            assertThat(ledger.exclusiveArea()).isNull();
            assertThat(ledger.dataSource()).isEqualTo(LedgerDataSource.BUILDING_HUB);
        });
    }

    @Test
    @DisplayName("항목이 하나면 item 이 배열이 아닌 객체로 온다 — 그대로 읽는다")
    void readsSingleItemObject() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(1,
                item("", "0", "업무시설", "서울특별시 시험구 시험길 19 (시험동)", "5120.00", "20150418")),
                MediaType.APPLICATION_JSON));

        Optional<BuildingLedgerDocument> document = clientOf(builder).fetch(lookup(PropertyType.OFFICETEL, KEY));

        assertThat(document).hasValueSatisfying(ledger -> {
            assertThat(ledger.buildingPurpose()).isEqualTo("업무시설");
            assertThat(ledger.approvalDate()).isEqualTo(LocalDate.of(2015, 4, 18));
        });
    }

    @Test
    @DisplayName("0건이면 items 가 빈 문자열로 온다 — 뗄 대장이 없어 빈 값이다(장애가 아니다)")
    void emptyItemsMeansNoLedger() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                "body":{"items":"","numOfRows":100,"pageNo":1,"totalCount":0}}}
                """, MediaType.APPLICATION_JSON));

        assertThat(clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY))).isEmpty();
    }

    @Test
    @DisplayName("조회 키가 없는 매물은 부르지 않고 빈 값이다 — 추측한 키로 떼면 다른 건물의 대장이다")
    void noLedgerKeyMeansNoCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(clientOf(builder).fetch(lookup(PropertyType.APARTMENT, null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("totalCount 가 한 페이지를 넘으면 다음 페이지를 부른다")
    void followsPages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(2,
                item("주차장", "1", "자동차관련시설", "", "900.00", "19921125")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(uriOf(2))).andRespond(withSuccess(body(2,
                item("101동", "0", "공동주택", "", "14544.66", "19921125")), MediaType.APPLICATION_JSON));

        Optional<BuildingLedgerDocument> document = clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).hasValueSatisfying(ledger -> assertThat(ledger.buildingPurpose()).isEqualTo("공동주택"));
    }

    @Test
    @DisplayName("resultCode 가 00 이 아니면 장애다 — EXTERNAL_API_UNAVAILABLE")
    void nonSuccessResultCodeIsFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess("""
                {"response":{"header":{"resultCode":"99","resultMsg":"APPLICATION ERROR"}}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY)))
                .isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("인증 오류는 _type=json 이어도 XML 로 온다 — 읽지 못하면 장애다")
    void xmlErrorBodyIsFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess("""
                <OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """, MediaType.TEXT_XML));

        assertThatThrownBy(() -> clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("동 고르기: 같은 주용도 주건축물이 여럿이면 도로명 · 건물번호가 매물 주소와 같은 행을 고른다")
    void selectsRowMatchingPropertyRoadAddress() {
        RealBuildingLedgerClient.TitleRow other = row("0", "공동주택", "서울특별시 시험구 다른길 5 (시험동)", "1000.00");
        RealBuildingLedgerClient.TitleRow matching = row("0", "공동주택", "서울특별시 시험구 시험길 19 (시험동)", "2000.00");

        assertThat(RealBuildingLedgerClient.select(List.of(other, matching), PropertyType.APARTMENT, PROPERTY_ADDRESS))
                .contains(matching);
    }

    @Test
    @DisplayName("동 고르기: 맞는 주소가 없으면 주건축물 · 맞는 주용도 중 응답 순서의 첫 행이다")
    void fallsBackToFirstMainBuildingOfMatchingPurpose() {
        RealBuildingLedgerClient.TitleRow annex = row("1", "공동주택", null, "100.00");
        RealBuildingLedgerClient.TitleRow shop = row("0", "판매시설", null, "300.00");
        RealBuildingLedgerClient.TitleRow first = row("0", "공동주택", null, "1000.00");
        RealBuildingLedgerClient.TitleRow second = row("0", "공동주택", null, "2000.00");

        assertThat(RealBuildingLedgerClient.select(List.of(annex, shop, first, second), PropertyType.APARTMENT,
                PROPERTY_ADDRESS)).contains(first);
    }

    @Test
    @DisplayName("동 고르기: 주용도가 모두 비면 빈 값이다 — 주용도를 지어내지 않는다")
    void noRowWithPurposeMeansNoLedger() {
        assertThat(RealBuildingLedgerClient.select(List.of(row("0", null, null, "1000.00")), PropertyType.APARTMENT,
                PROPERTY_ADDRESS)).isEmpty();
    }

    @Test
    @DisplayName("폴백은 값을 채운 대장도 빈 값도 아니라 EXTERNAL_API_UNAVAILABLE 을 던진다")
    void fallbackThrows() throws Exception {
        RealBuildingLedgerClient client = clientOf(RestClient.builder());
        Method fallback = RealBuildingLedgerClient.class
                .getDeclaredMethod("unavailable", BuildingLedgerLookup.class, Throwable.class);
        fallback.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, lookup(PropertyType.APARTMENT, KEY), new RuntimeException("서킷 오픈"));
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isInstanceOf(BusinessException.class)
                .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    @DisplayName("디코딩 키 · 빈 키면 기동에서 멈춘다")
    void rejectsDecodedKey() {
        assertThatThrownBy(() -> clientOf(RestClient.builder(), "abc+def/ghi=="))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clientOf(RestClient.builder(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 일일 상한(PROP-04) ----------

    @Test
    @DisplayName("일일 상한에 닿아 있으면 제공처를 부르지 않고 빈 값이다 — 예외(503 · 서킷 실패)가 아니다")
    void quotaExhaustedCallsNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(false);
        when(quota.remaining()).thenReturn(100L);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isEmpty();
        verify(quota, times(1)).tryAcquire();
    }

    @Test
    @DisplayName("요청(페이지)마다 상한 한 칸을 쓴다 — 두 페이지면 두 번")
    void countsEveryPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(2,
                item("주차장", "1", "자동차관련시설", "", "900.00", "19921125")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(uriOf(2))).andRespond(withSuccess(body(2,
                item("101동", "0", "공동주택", "", "14544.66", "19921125")), MediaType.APPLICATION_JSON));
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(true);
        when(quota.remaining()).thenReturn(100L);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isPresent();
        verify(quota, times(2)).tryAcquire();
    }

    @Test
    @DisplayName("둘째 페이지 앞에서 상한에 닿으면 첫 페이지 행(공동주택 주건축물)으로 고르지 않고 빈 값이다")
    void quotaExhaustedMidwayDiscardsPartialRows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(2,
                item("101동", "0", "공동주택", "", "14544.66", "19921125")), MediaType.APPLICATION_JSON));
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(true, false);
        when(quota.remaining()).thenReturn(100L);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isEmpty();
    }

    @Test
    @DisplayName("조회 키가 없으면 상한을 쓰지 않는다")
    void noKeyDoesNotCount() {
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);

        Optional<BuildingLedgerDocument> document =
                clientOf(RestClient.builder(), ENCODED_KEY, quota).fetch(lookup(PropertyType.APARTMENT, null));

        assertThat(document).isEmpty();
        verify(quota, times(0)).tryAcquire();
    }

    // ---------- 일일 한도가 찬 날의 사전 확인(#336) ----------

    @Test
    @DisplayName("일일 한도가 찬 날(remaining 0)은 초당 몫 · 일일 몫 · 제공처 요청 없이 빈 값이다")
    void quotaAlreadyExhaustedSkipsRateLimiterAndRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.remaining()).thenReturn(0L);
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota, limiter).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isEmpty();
        verify(limiter, never()).acquire();
        verify(quota, never()).tryAcquire();
    }

    @Test
    @DisplayName("일일 한도가 남은 날은 초당 몫 → 일일 몫 → 요청 순으로 기존처럼 간다")
    void quotaRemainingProceedsAsBefore() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(1,
                item("101동", "0", "공동주택", "", "14544.66", "19921125")), MediaType.APPLICATION_JSON));
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.remaining()).thenReturn(1L);
        when(quota.tryAcquire()).thenReturn(true);
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);
        when(limiter.acquire()).thenReturn(true);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota, limiter).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isPresent();
        InOrder order = inOrder(limiter, quota);
        order.verify(limiter).acquire();
        order.verify(quota).tryAcquire();
    }

    @Test
    @DisplayName("확인 뒤 한도가 차면(remaining 1 → tryAcquire false) 기존 경로대로 요청 없이 빈 값이다")
    void quotaFilledAfterCheckReturnsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.remaining()).thenReturn(1L);
        when(quota.tryAcquire()).thenReturn(false);
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);
        when(limiter.acquire()).thenReturn(true);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota, limiter).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isEmpty();
        verify(limiter, times(1)).acquire();
        verify(quota, times(1)).tryAcquire();
    }

    // ---------- 초당 상한 · 제공처 한도 응답(PROP-04, #318) ----------

    /** 2026-09-30 운영에서 받은 초당 한도 응답의 모양. {@code _type=json} 이어도 이 모양이다. 메시지 문구는 줄였다. */
    private static final String PER_SECOND_LIMIT_JSON = """
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
            "errMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR",
            "returnAuthMsg":"초당 서비스 요청제한 횟수 초과","returnReasonCode":"99"}}}
            """;

    @Test
    @DisplayName("초당 몫을 기다림 상한 안에 못 받으면 제공처를 부르지 않고 초당 한도 예외 — 일일 몫도 쓰지 않는다")
    void rateLimiterGivesUpWithoutCalling() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.remaining()).thenReturn(100L);
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);
        when(limiter.acquire()).thenReturn(false);

        assertThatThrownBy(() -> clientOf(builder, ENCODED_KEY, quota, limiter).fetch(lookup(PropertyType.APARTMENT, KEY)))
                .isInstanceOf(BuildingLedgerRateLimitedException.class);

        server.verify();
        verify(quota, never()).tryAcquire();
    }

    @Test
    @DisplayName("페이지마다 초당 몫을 먼저 받고 일일 몫을 받는다 — 두 페이지면 각각 두 번")
    void acquiresRateThenQuotaPerPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(body(2,
                item("주차장", "1", "자동차관련시설", "", "900.00", "19921125")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(uriOf(2))).andRespond(withSuccess(body(2,
                item("101동", "0", "공동주택", "", "14544.66", "19921125")), MediaType.APPLICATION_JSON));
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(true);
        when(quota.remaining()).thenReturn(100L);
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);
        when(limiter.acquire()).thenReturn(true);

        assertThat(clientOf(builder, ENCODED_KEY, quota, limiter).fetch(lookup(PropertyType.APARTMENT, KEY))).isPresent();

        server.verify();
        verify(limiter, times(2)).acquire();
        verify(quota, times(2)).tryAcquire();
    }

    @Test
    @DisplayName("제공처의 초당 한도 응답(JSON)은 장애(503)가 아니라 초당 한도 예외다")
    void perSecondLimitJsonIsRateLimited() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess(PER_SECOND_LIMIT_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY)))
                .isInstanceOf(BuildingLedgerRateLimitedException.class);
        server.verify();
    }

    @Test
    @DisplayName("제공처의 초당 한도 응답이 XML 로 와도 초당 한도 예외다")
    void perSecondLimitXmlIsRateLimited() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess("""
                <OpenAPI_ServiceResponse><cmmMsgHeader>
                <errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR</errMsg>
                <returnAuthMsg>초당 서비스 요청제한 횟수 초과</returnAuthMsg><returnReasonCode>99</returnReasonCode>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """, MediaType.TEXT_XML));

        assertThatThrownBy(() -> clientOf(builder).fetch(lookup(PropertyType.APARTMENT, KEY)))
                .isInstanceOf(BuildingLedgerRateLimitedException.class);
    }

    @Test
    @DisplayName("제공처의 일일 한도 응답이면 일일 카운터를 상한까지 채우고 빈 값이다 — 예외(503 · 서킷 실패)가 아니다")
    void dailyLimitResponseExhaustsQuota() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(uriOf(1))).andRespond(withSuccess("""
                <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
                <returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """, MediaType.TEXT_XML));
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(true);
        when(quota.remaining()).thenReturn(100L);

        Optional<BuildingLedgerDocument> document =
                clientOf(builder, ENCODED_KEY, quota).fetch(lookup(PropertyType.APARTMENT, KEY));

        server.verify();
        assertThat(document).isEmpty();
        verify(quota).exhaust();
    }

    @Test
    @DisplayName("한도 응답 구분: errMsg · returnAuthMsg 어느 쪽의 코드든 JSON · XML 모두 읽고, 다른 오류는 한도가 아니다")
    void providerLimitParsing() {
        assertThat(RealBuildingLedgerClient.providerLimitOf(PER_SECOND_LIMIT_JSON.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(RealBuildingLedgerClient.ProviderLimit.PER_SECOND);
        assertThat(RealBuildingLedgerClient.providerLimitOf("""
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE ERROR",
                "returnAuthMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}}
                """.getBytes(StandardCharsets.UTF_8))).isEqualTo(RealBuildingLedgerClient.ProviderLimit.DAILY);
        assertThat(RealBuildingLedgerClient.providerLimitOf("""
                <OpenAPI_ServiceResponse><cmmMsgHeader>
                <errMsg> LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR </errMsg>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """.getBytes(StandardCharsets.UTF_8))).isEqualTo(RealBuildingLedgerClient.ProviderLimit.DAILY);
        // 인증 오류 · 정상 응답 · 읽을 수 없는 본문은 한도가 아니다 — 장애 여부는 결과 코드 확인이 가른다.
        assertThat(RealBuildingLedgerClient.providerLimitOf("""
                <OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """.getBytes(StandardCharsets.UTF_8))).isEqualTo(RealBuildingLedgerClient.ProviderLimit.NONE);
        assertThat(RealBuildingLedgerClient.providerLimitOf(body(0, "[]").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(RealBuildingLedgerClient.ProviderLimit.NONE);
        assertThat(RealBuildingLedgerClient.providerLimitOf("<html>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(RealBuildingLedgerClient.ProviderLimit.NONE);
    }

    @Test
    @DisplayName("초당 한도 예외의 폴백은 503 으로 바꾸지 않고 그대로 올린다")
    void rateLimitFallbackRethrows() throws Exception {
        RealBuildingLedgerClient client = clientOf(RestClient.builder());
        Method fallback = RealBuildingLedgerClient.class.getDeclaredMethod("unavailable", BuildingLedgerLookup.class,
                BuildingLedgerRateLimitedException.class);
        fallback.setAccessible(true);
        BuildingLedgerRateLimitedException limited = new BuildingLedgerRateLimitedException("시험");

        assertThatThrownBy(() -> {
            try {
                fallback.invoke(client, lookup(PropertyType.APARTMENT, KEY), limited);
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }).isSameAs(limited);
    }

    @Test
    @DisplayName("폴백은 원인 예외의 종류 · 메시지를 WARN 으로 남기고 메시지의 인증키를 가린다")
    void fallbackLogsCauseWithMaskedKey() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RealBuildingLedgerClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RealBuildingLedgerClient client = clientOf(RestClient.builder());
            Method fallback = RealBuildingLedgerClient.class
                    .getDeclaredMethod("unavailable", BuildingLedgerLookup.class, Throwable.class);
            fallback.setAccessible(true);
            IllegalStateException cause = new IllegalStateException(
                    "I/O error on GET request for \"" + uriOf(1) + "\": Read timed out");

            assertThatThrownBy(() -> {
                try {
                    fallback.invoke(client, lookup(PropertyType.APARTMENT, KEY), cause);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            }).isInstanceOf(BusinessException.class);

            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains("java.lang.IllegalStateException")
                                .contains("Read timed out")
                                .contains("serviceKey=***")
                                .doesNotContain(ENCODED_KEY);
                        // 스택은 WARN 에 싣지 않는다(DEBUG).
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---------- 픽스처 ----------

    private RealBuildingLedgerClient clientOf(RestClient.Builder builder) {
        return clientOf(builder, ENCODED_KEY);
    }

    private RealBuildingLedgerClient clientOf(RestClient.Builder builder, String apiKey) {
        BuildingLedgerDailyQuota quota = mock(BuildingLedgerDailyQuota.class);
        when(quota.tryAcquire()).thenReturn(true);
        when(quota.remaining()).thenReturn(100L);
        return clientOf(builder, apiKey, quota);
    }

    private RealBuildingLedgerClient clientOf(RestClient.Builder builder, String apiKey,
            BuildingLedgerDailyQuota quota) {
        BuildingLedgerRateLimiter limiter = mock(BuildingLedgerRateLimiter.class);
        when(limiter.acquire()).thenReturn(true);
        return clientOf(builder, apiKey, quota, limiter);
    }

    private RealBuildingLedgerClient clientOf(RestClient.Builder builder, String apiKey,
            BuildingLedgerDailyQuota quota, BuildingLedgerRateLimiter limiter) {
        ExternalApiProperties.ClientSettings settings =
                new ExternalApiProperties.ClientSettings("real", BASE_URL, apiKey, null, null, null);
        return new RealBuildingLedgerClient(
                builder.build(), new ExternalApiProperties(null, null, null, settings, null, null), quota, limiter);
    }

    private static BuildingLedgerLookup lookup(PropertyType type, LedgerLookupKey key) {
        return new BuildingLedgerLookup(1024L,
                new PropertyNaturalKey(PROPERTY_ADDRESS, new BigDecimal("84.90"), 7, 300_000_000L, 0L),
                "김임대", type, key);
    }

    private static RealBuildingLedgerClient.TitleRow row(String mainAtchCode, String purpose, String roadAddress,
            String totalFloorArea) {
        return new RealBuildingLedgerClient.TitleRow("서울특별시 시험구 시험동 702번지", roadAddress, mainAtchCode, purpose,
                "철근콘크리트구조", null, new BigDecimal(totalFloorArea), null);
    }

    private static String body(int totalCount, String item) {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                "body":{"items":{"item":%s},"numOfRows":100,"pageNo":1,"totalCount":%d}}}
                """.formatted(item, totalCount);
    }

    /** 실응답 형태의 표제부 한 행. 면적은 JSON 숫자, 건축면적은 미기재(0), 빈 값은 공백 한 칸이다. */
    private static String item(String dongNm, String mainAtchGbCd, String mainPurpsCdNm, String newPlatPlc,
            String totArea, String useAprDay) {
        return """
                {"rnum":1,"platPlc":"서울특별시 시험구 시험동 702번지","sigunguCd":"11110","bjdongCd":"17400",
                "platGbCd":"0","bun":"0702","ji":"0000","regstrKindCdNm":"표제부","newPlatPlc":"%s",
                "bldNm":"시험아파트","splotNm":" ","dongNm":"%s","mainAtchGbCd":"%s","mainAtchGbCdNm":"주건축물",
                "platArea":24923.1,"archArea":0,"totArea":%s,"strctCd":"21","strctCdNm":"철근콘크리트구조",
                "mainPurpsCd":"02000","mainPurpsCdNm":"%s","etcPurps":"공동주택(아파트)","hhldCnt":201,
                "grndFlrCnt":14,"pmsDay":" ","stcnsDay":" ","useAprDay":"%s","crtnDay":"20220813"}
                """.formatted(newPlatPlc, dongNm, mainAtchGbCd, totArea, mainPurpsCdNm, useAprDay);
    }
}
