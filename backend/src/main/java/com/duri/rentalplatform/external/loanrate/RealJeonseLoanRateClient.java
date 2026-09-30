package com.duri.rentalplatform.external.loanrate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.ExternalFallbackLog;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한국주택금융공사 전세자금대출 고객특성별 금리 실제 호출 구현.
 *
 * <p>제공처 문서 — https://www.data.go.kr/data/15082044/openapi.do (참고문서 「전세대출금리다차원조회_V1.7」, 수정일
 * 2025-07-03, 확인일 2026-09-30). 요청 주소는 {@code {base-url}/rent-loan-rate-multi-dimensional-info/dimensional-list}.
 *
 * <p>요청 파라미터 — 필수 {@code serviceKey}(URL 인코딩된 인증키) · {@code pageNo} · {@code numOfRows} · {@code loanYm}(YYYYMM,
 * 또는 L1M · L3M · L1Y), 선택 {@code houseTycd} · {@code dataType}(XML/JSON) 외 고객 특성(CB 등급 · 직업 · 연령 · 소득 · 부채).
 * 고객 특성은 넘기지 않는다 — 전체 차주의 은행별 금리를 받는다.
 *
 * <p>{@code loanYm} 은 연월(YYYYMM)로 보낸다. 응답에 기준월이 없어서, L1M(최근 1개월)으로 받으면 저장한 금리가 어느 달 것인지
 * 식별할 수 없다. 2026-09-30 실호출에서 {@code loanYm=202608} 과 {@code L1M} 의 결과가 서로 달랐다 — L1M 은 달력 월이 아니다.
 *
 * <p>응답 항목 — 제공처 문서 출력결과 표: {@code avgLoanRat} 「산술평균대출금리」, <b>{@code avgLoanRat2} 「가중평균대출금리」</b>,
 * {@code bankNm} 은행명, {@code cnt} 대출건수, {@code loanAmt} 대출실행금액, {@code maxLoanRat} · {@code minLoanRat} 최대 · 최소
 * 대출금리. 금리 · 금액은 문자열로 온다({@code "4.36"} · {@code "29177470000"}). 여기서는 가중평균 금리와 실행금액만 옮긴다.
 *
 * <p>인증키는 포털의 인코딩 키를 {@code .env} 에 그대로 넣고 다시 인코딩하지 않는다 — {@link #request} 참고. 전월세 실거래가와 같은
 * 키(DATA_GO_KR_API_KEY)이며 활용 신청은 서비스마다 따로다. 개발계정 트래픽은 일 1,000건(같은 문서).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external.jeonse-loan-rate", name = "mode", havingValue = "real")
public class RealJeonseLoanRateClient implements JeonseLoanRateClient {

    static final String OPERATION_PATH = "rent-loan-rate-multi-dimensional-info/dimensional-list";

    private static final DateTimeFormatter LOAN_YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** 은행 수는 20 안쪽이다(2026-09-30 실호출 아파트 15 · 오피스텔 13). 한 페이지에 다 받는다. */
    private static final int ROWS_PER_PAGE = 100;

    /** 페이지 순회 상한. totalCount 가 틀어져도 무한 루프에 빠지지 않게 막는다. */
    private static final int MAX_PAGES = 5;

    private static final String SUCCESS_RESULT_CODE = "00";

    /** 인코딩 키는 비예약 문자와 퍼센트 인코딩으로만 이루어진다. */
    private static final Pattern ENCODED_KEY = Pattern.compile("(?:[A-Za-z0-9\\-._~]|%[0-9A-Fa-f]{2})+");

    private static final JsonMapper JSON = new JsonMapper();

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealJeonseLoanRateClient(
            @Qualifier("jeonseLoanRateRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.jeonseLoanRate();
        if (settings.apiKey() == null || !ENCODED_KEY.matcher(settings.apiKey()).matches()) {
            // 디코딩 키면 요청마다 403 이 나고 폴백이 외부 장애로 바꿔 설정 오류가 가려진다. 기동에서 멈춘다.
            throw new IllegalStateException(
                    "external.jeonse-loan-rate.api-key 에는 공공데이터포털의 인코딩 키를 넣는다 — 비었거나 디코딩 키다");
        }
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<BankLoanRate> findBankLoanRates(LoanRateQuery query) {
        List<BankLoanRate> collected = new ArrayList<>();
        int seen = 0;
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            JsonNode root = parse(request(query, pageNo));
            verifyResultCode(root);

            JsonNode body = root.path("body");
            List<JsonNode> items = itemsOf(body.path("items"));
            seen += items.size();
            for (JsonNode item : items) {
                BankLoanRate rate = toRate(item);
                if (rate != null) {
                    collected.add(rate);
                }
            }
            if (items.isEmpty() || seen >= body.path("totalCount").asLong(0)) {
                break;
            }
        }
        return collected;
    }

    /**
     * 서킷이 열렸거나 재시도가 모두 실패했을 때의 폴백. 빈 목록을 돌려주지 않는다 — 「그 달 실적 없음」과 같아지면 갱신이 실패를
     * 「은행 0곳」으로 기록한다.
     */
    @SuppressWarnings("unused")
    private List<BankLoanRate> unavailable(LoanRateQuery query, Throwable cause) {
        ExternalFallbackLog.warn(log, "전세자금대출 금리", cause);
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    /**
     * 요청 URI 를 이미 인코딩된 것으로 조립한다({@code build(true)}). 인코딩 키의 {@code %} 가 {@code %25} 로 다시 인코딩되면
     * 「등록되지 않은 서비스키」가 난다. 나머지 값은 숫자 · 영문뿐이다.
     */
    private byte[] request(LoanRateQuery query, int pageNo) {
        URI uri = UriComponentsBuilder.fromUriString(settings.baseUrl())
                .path("/" + OPERATION_PATH)
                .queryParam("serviceKey", settings.apiKey())
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", ROWS_PER_PAGE)
                .queryParam("loanYm", query.loanMonth().format(LOAN_YM))
                .queryParam("houseTycd", query.houseType().getCode())
                .queryParam("dataType", "JSON")
                .build(true)
                .toUri();
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
    }

    /** 바이트로 받아 파싱한다. 인증 오류 등은 JSON 요청에도 XML 로 올 수 있어, 파싱 실패는 외부 장애로 본다. */
    private JsonNode parse(byte[] json) {
        if (json == null || json.length == 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        try {
            return JSON.readTree(json);
        } catch (JacksonException cause) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    /** 오류도 HTTP 200 에 담겨 온다. 본문의 resultCode 를 보지 않으면 오류가 「은행 0곳」으로 둔갑한다. */
    private void verifyResultCode(JsonNode root) {
        if (!SUCCESS_RESULT_CODE.equals(root.path("header").path("resultCode").asString(""))) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    /**
     * {@code items} 를 목록으로 편다. 배열이 정상 형태이고(2026-09-30 실호출), 결과가 없을 때 빈 문자열 · 누락, 한 건일 때 객체로
     * 오는 경우도 받아 준다 — 공공데이터포털 JSON 응답에서 흔한 형태이나 이 API 에서 확인하지는 않았다.
     */
    private List<JsonNode> itemsOf(JsonNode items) {
        List<JsonNode> nodes = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(nodes::add);
        } else if (items.isObject()) {
            nodes.add(items);
        }
        return nodes;
    }

    /**
     * 한 은행을 우리 형태로 옮긴다. 은행명 · 가중평균 금리 · 실행금액 중 하나라도 비었거나 금리가 0 이하면 그 은행만 버린다 — 금리
     * 0 은 한도 역산의 분모라 계산을 성립시키지 못한다.
     */
    private BankLoanRate toRate(JsonNode item) {
        String bankName = item.path("bankNm").asString("").strip();
        BigDecimal rate = decimalOf(item.path("avgLoanRat2").asString(""));
        BigDecimal amount = decimalOf(item.path("loanAmt").asString(""));
        if (bankName.isEmpty() || rate == null || amount == null || rate.signum() <= 0) {
            return null;
        }
        try {
            return new BankLoanRate(bankName, rate, amount.longValueExact());
        } catch (ArithmeticException notWholeWon) {
            return null;
        }
    }

    private BigDecimal decimalOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.strip());
        } catch (NumberFormatException cause) {
            return null;
        }
    }
}
