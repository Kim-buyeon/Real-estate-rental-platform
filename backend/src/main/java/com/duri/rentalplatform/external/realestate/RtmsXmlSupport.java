package com.duri.rentalplatform.external.realestate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 국토교통부 실거래가(RTMS) 응답 읽기. 전월세 · 매매 Real 구현이 함께 쓴다.
 *
 * <p>두 연동은 같은 제공처 · 같은 인증키 · 같은 응답 골격(header 의 resultCode, body 의 items · totalCount)이고 다른 것은
 * 항목의 필드뿐이다. 파서 보안 설정 · 결과 코드 확인 · 금액 환산이 구현마다 따로 있으면 한쪽만 고쳐진다.
 */
final class RtmsXmlSupport {

    static final DateTimeFormatter DEAL_YMD = DateTimeFormatter.ofPattern("yyyyMM");

    /** 한 페이지에 요청하는 건수. */
    static final int ROWS_PER_PAGE = 1000;

    /** 페이지 순회 상한. 응답의 totalCount 를 믿되, 값이 틀어져도 무한 루프에 빠지지 않게 막는다. */
    static final int MAX_PAGES = 50;

    private static final List<String> SUCCESS_RESULT_CODES = List.of("00", "000");

    /** 인코딩 키는 비예약 문자와 퍼센트 인코딩으로만 이루어진다. */
    private static final Pattern ENCODED_KEY = Pattern.compile("(?:[A-Za-z0-9\\-._~]|%[0-9A-Fa-f]{2})+");

    /** 금액 단위 환산. 제공처는 만원 단위로 준다. */
    private static final long AMOUNT_UNIT = 10_000L;

    private RtmsXmlSupport() {
    }

    /**
     * 인코딩 키가 아니면 기동에서 멈춘다.
     *
     * <p>디코딩 키({@code +} · {@code /} · {@code =} 포함)가 들어오면 요청마다 URI 조립이 실패하거나
     * 403 이 나고, 재시도 · 서킷을 거쳐 폴백이 {@code EXTERNAL_API_UNAVAILABLE} 로 바꾼다. 그러면 설정
     * 오류가 25개 구 전부의 「연동실패」로 기록되어 외부 장애와 구분되지 않는다.
     *
     * @param settingKey 오류 문구에 넣을 설정 키 이름
     */
    static void verifyEncodedKey(String apiKey, String settingKey) {
        if (apiKey == null || !ENCODED_KEY.matcher(apiKey).matches()) {
            throw new IllegalStateException(
                    settingKey + " 에는 공공데이터포털의 인코딩 키를 넣는다 — 비었거나 디코딩 키다");
        }
    }

    /**
     * 바이트 그대로 파서에 넘긴다. 문자열로 받으면 Content-Type 에 charset 이 빠진 응답을 ISO-8859-1 로
     * 읽어 한글이 깨진다. 파서는 XML 선언의 encoding 을 따른다.
     */
    static Document parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
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
                    .parse(new ByteArrayInputStream(xml));
        } catch (Exception cause) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    /**
     * 이 API 는 오류도 HTTP 200 에 담아 보낸다. 본문의 resultCode 를 보지 않으면 오류 응답이
     * 「거래 0건」으로 둔갑한다.
     */
    static void verifyResultCode(Document document) {
        String resultCode = readText(document.getDocumentElement(), "resultCode");
        if (resultCode != null && !SUCCESS_RESULT_CODES.contains(resultCode)) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
    }

    /** 응답의 전체 건수. 페이지 순회를 끝낼 때 쓴다. */
    static int totalCount(Document document) {
        Integer value = readInteger(document.getDocumentElement(), "totalCount");
        return value == null ? 0 : value;
    }

    /**
     * 이 페이지에 온 항목 수. 변환에서 버린 건까지 센다.
     *
     * <p>페이지 순회의 종료는 이 값으로 판단한다. 변환한 건수로 판단하면, 필수 값이 빈 건을 버린 달은 모은 건수가 totalCount 에
     * 끝내 닿지 않아 빈 페이지를 한 번 더 부르고, 한 페이지의 항목이 모두 버려지면 뒤 페이지가 남았는데도 순회가 끝난다.
     */
    static int itemCount(Document document) {
        return document.getElementsByTagName("item").getLength();
    }

    /**
     * 항목마다 변환한다. 변환 함수가 {@code null} 을 돌려준 건(필수 값이 빈 건)은 버린다 — 한 건 때문에 그 달 전체를
     * 버리지 않는다.
     */
    static <T> List<T> readItems(Document document, Function<Element, T> converter) {
        NodeList items = document.getElementsByTagName("item");
        List<T> converted = new ArrayList<>(items.getLength());
        for (int index = 0; index < items.getLength(); index++) {
            Node node = items.item(index);
            if (node instanceof Element item) {
                T value = converter.apply(item);
                if (value != null) {
                    converted.add(value);
                }
            }
        }
        return converted;
    }

    /** 계약일. 제공처는 연 · 월 · 일을 세 필드로 준다. 하나라도 비거나 날짜가 아니면 {@code null}. */
    static LocalDate readDealDate(Element item) {
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
    static Long readAmount(Element item, String tagName) {
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

    static BigDecimal readDecimal(Element item, String tagName) {
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

    static Integer readInteger(Element element, String tagName) {
        String raw = readText(element, tagName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException cause) {
            return null;
        }
    }

    /** 앞뒤 공백을 걷어 돌려준다. 제공처는 빈 값을 공백 한 칸({@code <cdealType> </cdealType>})으로 보내기도 한다. */
    static String readText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
