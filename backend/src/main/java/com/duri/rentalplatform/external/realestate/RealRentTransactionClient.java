package com.duri.rentalplatform.external.realestate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 국토교통부 전월세 실거래가 실제 호출 구현.
 *
 * <p>제공처 문서 — 아파트 전월세 https://www.data.go.kr/data/15126474/openapi.do,
 * 오피스텔 전월세 https://www.data.go.kr/data/15126475/openapi.do.
 * 두 서비스는 인증키가 같고(DATA_GO_KR_API_KEY) <b>활용 신청은 각각</b>이다.
 *
 * <p>요청 파라미터는 {@code serviceKey} · {@code LAWD_CD}(법정동 코드 앞 5자리) ·
 * {@code DEAL_YMD}(계약년월 YYYYMM) · {@code pageNo} · {@code numOfRows} 다.
 * 응답은 XML 이며 JSON 옵션이 없다. XML 파서를 따로 들이지 않고 JDK 내장 DOM 으로 읽는다.
 *
 * <p>인증키는 <b>디코딩(일반) 키</b>를 {@code .env} 에 넣는다. 인코딩된 키를 넣으면 여기서 한 번 더
 * 인코딩되어 인증이 깨진다.
 *
 * <p>호출 로깅은 이 클래스에 쓰지 않는다. 「외부 API 호출 로깅」은 관점의 몫이며(횡단 관심사 설계서 1.1),
 * 포인트컷 애노테이션은 아직 만들어지지 않았다. 그 기반 작업이 끝나면 애노테이션만 붙인다.
 */
@Component
@ConditionalOnProperty(prefix = "external.rent-transaction", name = "mode", havingValue = "real")
public class RealRentTransactionClient implements RentTransactionClient {

    private static final DateTimeFormatter DEAL_YMD = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int ROWS_PER_PAGE = 1000;

    /** 페이지 순회 상한. 응답의 totalCount 를 믿되, 값이 틀어져도 무한 루프에 빠지지 않게 막는다. */
    private static final int MAX_PAGES = 50;

    private static final List<String> SUCCESS_RESULT_CODES = List.of("00", "000");

    /** 금액 단위 환산. 제공처는 만원 단위로 준다. */
    private static final long AMOUNT_UNIT = 10_000L;

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealRentTransactionClient(
            @Qualifier("rentTransactionRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.rentTransaction();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
        List<RentTransaction> collected = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            Document document = parse(request(query, pageNo));
            verifyResultCode(document);

            List<RentTransaction> page = readItems(document, query);
            collected.addAll(page);

            int totalCount = readInt(document.getDocumentElement(), "totalCount");
            if (page.isEmpty() || collected.size() >= totalCount) {
                break;
            }
        }
        return collected;
    }

    /**
     * 서킷이 열려 있거나 재시도가 모두 실패했을 때의 폴백.
     *
     * <p>빈 목록을 돌려주지 않는다. 「해당 월에 거래가 없었다」와 「조회하지 못했다」가 같은 값이 되면
     * 적재가 실패를 성공으로 기록하고, 그 달 표본이 빠진 채 시세 중앙값이 산출된다. 시세는 RISK-02
     * 전세가율의 분모이므로 폴백이 값을 지어내면 판정이 조용히 틀어진다.
     */
    @SuppressWarnings("unused")
    private List<RentTransaction> unavailable(RentTransactionQuery query, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    private String request(RentTransactionQuery query, int pageNo) {
        RentBuildingType type = query.buildingType();
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/{servicePath}/{operation}")
                        .queryParam("serviceKey", settings.apiKey())
                        .queryParam("LAWD_CD", query.lawdCode())
                        .queryParam("DEAL_YMD", query.contractYearMonth().format(DEAL_YMD))
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", ROWS_PER_PAGE)
                        .build(type.getServicePath(), type.getOperation()))
                .retrieve()
                .body(String.class);
    }

    private Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 외부에서 받은 XML 이다. DTD 와 외부 엔티티를 막지 않으면 XXE 경로가 열린다.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception cause) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    /**
     * 이 API 는 오류도 HTTP 200 에 담아 보낸다. 본문의 resultCode 를 보지 않으면 오류 응답이
     * 「거래 0건」으로 둔갑한다.
     */
    private void verifyResultCode(Document document) {
        String resultCode = readText(document.getDocumentElement(), "resultCode");
        if (resultCode != null && !SUCCESS_RESULT_CODES.contains(resultCode)) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    private List<RentTransaction> readItems(Document document, RentTransactionQuery query) {
        NodeList items = document.getElementsByTagName("item");
        List<RentTransaction> transactions = new ArrayList<>(items.getLength());
        for (int index = 0; index < items.getLength(); index++) {
            Node node = items.item(index);
            if (node instanceof Element item) {
                RentTransaction transaction = toTransaction(item, query);
                if (transaction != null) {
                    transactions.add(transaction);
                }
            }
        }
        return transactions;
    }

    /**
     * 한 건을 우리 형태로 옮긴다. 필수 값이 비어 있으면 그 건만 버린다 — 한 건 때문에 그 달 전체를
     * 버리지 않는다.
     */
    private RentTransaction toTransaction(Element item, RentTransactionQuery query) {
        BigDecimal areaSqm = readDecimal(item, "excluUseAr");
        Long deposit = readAmount(item, "deposit");
        LocalDate contractDate = readContractDate(item);
        if (areaSqm == null || deposit == null || contractDate == null) {
            return null;
        }
        Long monthlyRent = readAmount(item, "monthlyRent");
        // 아파트 서비스는 aptNm, 오피스텔 서비스는 offiNm 으로 건물명을 준다.
        String buildingName = firstNonBlank(readText(item, "aptNm"), readText(item, "offiNm"));

        return new RentTransaction(
                query.lawdCode(),
                readText(item, "umdNm"),
                buildingName,
                readText(item, "jibun"),
                areaSqm,
                readInteger(item, "floor"),
                deposit,
                monthlyRent == null ? 0L : monthlyRent,
                contractDate,
                readInteger(item, "buildYear"),
                query.buildingType(),
                query.buildingType().getDataSource());
    }

    private LocalDate readContractDate(Element item) {
        Integer year = readInteger(item, "dealYear");
        Integer month = readInteger(item, "dealMonth");
        Integer day = readInteger(item, "dealDay");
        if (year == null || month == null || day == null) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException cause) {
            return null;
        }
    }

    /** 금액은 만원 단위 문자열로 오고 천 단위 콤마가 붙어 있다. 원 단위 정수로 바꾼다. */
    private Long readAmount(Element item, String tagName) {
        String raw = readText(item, tagName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.replace(",", "")) * AMOUNT_UNIT;
        } catch (NumberFormatException cause) {
            return null;
        }
    }

    private BigDecimal readDecimal(Element item, String tagName) {
        String raw = readText(item, tagName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException cause) {
            return null;
        }
    }

    private Integer readInteger(Element item, String tagName) {
        String raw = readText(item, tagName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException cause) {
            return null;
        }
    }

    private int readInt(Element element, String tagName) {
        Integer value = readInteger(element, tagName);
        return value == null ? 0 : value;
    }

    private String readText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
