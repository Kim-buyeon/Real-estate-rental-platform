package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 국토교통부 건축HUB 건축물대장정보 서비스 실제 호출 구현.
 *
 * <p>제공처 — 공공데이터포털 「국토교통부_건축HUB_건축물대장정보 서비스」
 * ({@code https://apis.data.go.kr/1613000/BldRgstHubService}). 쓰는 기능은 표제부 조회({@code getBrTitleInfo}) 하나다.
 * 요청은 {@code serviceKey} · {@code sigunguCd} · {@code bjdongCd} · {@code bun} · {@code ji} · {@code numOfRows} ·
 * {@code pageNo} · {@code _type=json} 이다.
 *
 * <p>응답에서 쓰는 필드(2026-09-30 종로구 창신동 702 실호출 응답으로 확인):
 * <ul>
 *   <li>{@code response.header.resultCode} — {@code "00"} 이 정상. 그 밖은 장애로 본다</li>
 *   <li>{@code response.body.totalCount} · {@code response.body.items.item[]} — 표제부 행. 동마다 한 행이다</li>
 *   <li>{@code platPlc}(대지위치, 지번 주소 · 「번지」로 끝난다) · {@code newPlatPlc}(도로명대지위치)</li>
 *   <li>{@code mainAtchGbCd} — {@code "0"} 이 주건축물, 그 밖은 부속건축물</li>
 *   <li>{@code mainPurpsCdNm}(주용도) · {@code strctCdNm}(구조) · {@code archArea}(건축면적) · {@code totArea}(연면적) ·
 *       {@code useAprDay}(사용승인일, {@code yyyyMMdd})</li>
 * </ul>
 * 면적은 JSON 숫자로 오고, 기재되지 않은 면적은 {@code 0} 으로 온다(같은 응답의 {@code archArea}). 0 은 면적으로 옮기지 않는다.
 *
 * <p><b>이 서비스가 주지 않는 것</b> — 같은 서비스의 9개 기능 어디에도 위반건축물 여부 · 소유자 항목이 없다(2026-09-30 실호출
 * 확인). 두 값은 null(확인하지 못함)로 둔다. 판정이 null 을 어떻게 다루는지는 명의 · 문서 정합 계산기가 정한다.
 *
 * <p><b>전용면적을 떼지 않는 이유</b> — 호별 전용면적은 전유공용면적 조회({@code getBrExposPubuseAreaInfo})에만 있는데, 필지
 * 단위로만 걸러지고(동 · 호명은 걸 수 있으나 매물은 동 · 호를 모른다) 한 페이지가 최대 100행이다. 같은 실호출에서 창신동 702
 * 한 필지가 5,167행(52쪽)이었다. 매물 하나에 수십 번 호출해야 하므로 떼지 않고 null 로 둔다.
 *
 * <p>인증키는 실거래가와 같은 {@code DATA_GO_KR_API_KEY} 이며 포털의 <b>인코딩 키</b>를 그대로 넣는다 — {@link #request} 참고.
 */
@Component
@ConditionalOnProperty(prefix = "external.building-ledger", name = "mode", havingValue = "real")
public class RealBuildingLedgerClient implements BuildingLedgerClient {

    static final String TITLE_OPERATION = "getBrTitleInfo";

    /** 한 페이지에 요청하는 건수. 제공처의 최댓값이다. */
    static final int ROWS_PER_PAGE = 100;

    /** 페이지 순회 상한. 응답의 totalCount 를 믿되, 값이 틀어져도 끝없이 돌지 않게 막는 가드다. 한 필지 표제부는 동 수만큼이다. */
    static final int MAX_PAGES = 10;

    private static final String SUCCESS_RESULT_CODE = "00";
    private static final String MAIN_BUILDING = "0";

    /** 매물 유형별로 표제부에서 찾을 주용도 — 건축법 시행령 별표 1 제2호 공동주택 · 제14호 업무시설(오피스텔). */
    private static final String APARTMENT_PURPOSE = "공동주택";
    private static final String OFFICETEL_PURPOSE = "업무시설";

    private static final DateTimeFormatter APPROVAL_DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern ENCODED_KEY = Pattern.compile("(?:[A-Za-z0-9\\-._~]|%[0-9A-Fa-f]{2})+");
    private static final Pattern WHITESPACES = Pattern.compile("\\s+");
    /** 도로명 주소 뒤의 참고항목 — {@code "... 19 (창신동)"} 의 괄호 부분. */
    private static final Pattern ROAD_ADDRESS_NOTE = Pattern.compile("\\s*\\(.*\\)\\s*$");
    private static final Pattern LOT_SUFFIX = Pattern.compile("\\s*번지\\s*$");

    private static final int AREA_SCALE = 2;
    /** {@code building_ledger.building_area} NUMERIC(7, 2) 가 담을 수 있는 최댓값. 넘으면 저장이 통째로 실패한다. */
    private static final BigDecimal MAX_BUILDING_AREA = new BigDecimal("99999.99");

    private static final JsonMapper JSON = new JsonMapper();

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealBuildingLedgerClient(
            @Qualifier("buildingLedgerRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.buildingLedger();
        // 인코딩 키가 아니면 기동에서 멈춘다. 디코딩 키가 들어오면 요청마다 403 이 나고 재시도 · 서킷을 거쳐 503 으로 바뀌어,
        // 설정 오류가 외부 장애와 구분되지 않는다 — 실거래가 Real 과 같은 이유.
        if (settings.apiKey() == null || !ENCODED_KEY.matcher(settings.apiKey()).matches()) {
            throw new IllegalStateException(
                    "external.building-ledger.api-key 에는 공공데이터포털의 인코딩 키를 넣는다 — 비었거나 디코딩 키다");
        }
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public Optional<BuildingLedgerDocument> fetch(BuildingLedgerLookup lookup) {
        LedgerLookupKey key = lookup.ledgerKey();
        if (key == null) {
            // 조회 키가 없으면 어느 필지인지 모른다. 부르지 않는다 — 추측한 키로 떼면 다른 건물의 대장이다.
            return Optional.empty();
        }
        List<TitleRow> rows = fetchTitleRows(key);
        return select(rows, lookup.propertyType(), lookup.naturalKey().address()).map(RealBuildingLedgerClient::toDocument);
    }

    /**
     * 서킷이 열려 있거나 재시도가 모두 실패했을 때의 폴백. 값을 채운 대장도 빈 값도 돌려주지 않는다 — 빈 값은 「뗄 대장이
     * 없다」라 수집 서비스가 장애를 자료 문제로 기록하게 된다.
     */
    @SuppressWarnings("unused")
    private Optional<BuildingLedgerDocument> unavailable(BuildingLedgerLookup lookup, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    private List<TitleRow> fetchTitleRows(LedgerLookupKey key) {
        List<TitleRow> rows = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            JsonNode body = parseBody(request(key, pageNo));
            List<JsonNode> items = items(body);
            items.forEach(item -> rows.add(TitleRow.from(item)));
            if (items.isEmpty() || rows.size() >= body.path("totalCount").asInt(0)) {
                break;
            }
        }
        return rows;
    }

    /**
     * 요청 URI 를 <b>이미 인코딩된 것으로</b> 조립한다({@code build(true)}). 인코딩 키를 RestClient 의 URI 빌더에 맡기면
     * {@code %} 가 {@code %25} 로 한 번 더 인코딩되어 403 이 난다 — 실거래가 Real 과 같다. 나머지 파라미터는 숫자뿐이다.
     */
    private byte[] request(LedgerLookupKey key, int pageNo) {
        URI uri = UriComponentsBuilder.fromUriString(settings.baseUrl())
                .pathSegment(TITLE_OPERATION)
                .queryParam("serviceKey", settings.apiKey())
                .queryParam("sigunguCd", key.sigunguCode())
                .queryParam("bjdongCd", key.bjdongCode())
                .queryParam("bun", key.bun())
                .queryParam("ji", key.ji())
                .queryParam("numOfRows", ROWS_PER_PAGE)
                .queryParam("pageNo", pageNo)
                .queryParam("_type", "json")
                .build(true)
                .toUri();
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
    }

    /**
     * 본문을 읽고 결과 코드를 확인한다. 바이트로 받아 파서에 넘긴다 — 문자열로 받으면 charset 이 빠진 응답의 한글이 깨진다.
     * 인증 오류는 {@code _type=json} 이어도 XML 로 오므로 파싱 실패도 장애로 본다.
     */
    private static JsonNode parseBody(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        JsonNode response;
        try {
            response = JSON.readTree(payload).path("response");
        } catch (JacksonException cause) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        if (!SUCCESS_RESULT_CODE.equals(response.path("header").path("resultCode").asString(""))) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        return response.path("body");
    }

    /**
     * 항목 목록. 제공처는 0건이면 {@code items} 를 빈 문자열로, 1건이면 {@code item} 을 배열이 아닌 객체로 보낸다 — 공공데이터포털
     * JSON 변환의 관행이라 셋 다 받는다.
     */
    private static List<JsonNode> items(JsonNode body) {
        JsonNode item = body.path("items").path("item");
        if (item.isArray()) {
            return new ArrayList<>(item.values());
        }
        return item.isObject() ? List.of(item) : List.of();
    }

    /**
     * 표제부 여러 행(동) 중 매물의 대장으로 쓸 한 행을 고른다. 뒤 단계는 앞 단계가 남긴 행 안에서만 좁히고, 좁힌 결과가 비면
     * 그 단계를 건너뛴다.
     *
     * <ol>
     *   <li>주용도가 빈 행은 버린다 — {@code building_ledger.building_purpose} 는 NOT NULL 이고 주용도를 지어내지 않는다.</li>
     *   <li>주건축물({@code mainAtchGbCd = "0"})만 남긴다. 부속건축물(주차장 · 관리동)은 매물이 있는 건물이 아니다.</li>
     *   <li>매물 유형에 맞는 주용도만 남긴다 — 아파트는 공동주택, 오피스텔은 업무시설. 단지 안 상가동이 여기서 빠진다.</li>
     *   <li>도로명대지위치의 도로명 · 건물번호(괄호 참고항목을 뗀 부분)가 매물 주소와 같은 행만 남긴다. 한 필지에 도로명
     *       주소가 다른 건물이 여럿일 때 가른다.</li>
     *   <li>남은 행 중 응답 순서의 첫 행. 같은 단지의 동들은 매물(동 · 호를 모른다)로는 더 가를 근거가 없다.</li>
     * </ol>
     * 단지명(건물명)으로는 고르지 않는다 — 매물이 건물명을 저장하지 않는다.
     */
    static Optional<TitleRow> select(List<TitleRow> rows, PropertyType propertyType, String propertyAddress) {
        List<TitleRow> candidates = rows.stream().filter(row -> row.mainPurpose() != null).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates = narrow(candidates, row -> MAIN_BUILDING.equals(row.mainAtchCode()));
        String purpose = switch (propertyType) {
            case APARTMENT -> APARTMENT_PURPOSE;
            case OFFICETEL -> OFFICETEL_PURPOSE;
        };
        candidates = narrow(candidates, row -> purpose.equals(row.mainPurpose()));
        String propertyRoad = roadPart(propertyAddress);
        candidates = narrow(candidates, row -> propertyRoad != null && propertyRoad.equals(roadPart(row.roadAddress())));
        return Optional.of(candidates.getFirst());
    }

    private static List<TitleRow> narrow(List<TitleRow> rows, Predicate<TitleRow> condition) {
        List<TitleRow> narrowed = rows.stream().filter(condition).toList();
        return narrowed.isEmpty() ? rows : narrowed;
    }

    /** 도로명 주소에서 괄호 참고항목을 떼고 공백을 접는다. 비었으면 null. */
    private static String roadPart(String roadAddress) {
        if (roadAddress == null || roadAddress.isBlank()) {
            return null;
        }
        return collapse(ROAD_ADDRESS_NOTE.matcher(roadAddress).replaceFirst(""));
    }

    private static BuildingLedgerDocument toDocument(TitleRow row) {
        return new BuildingLedgerDocument(
                ledgerAddress(row),
                null,
                row.mainPurpose(),
                row.structure(),
                row.buildingArea() == null || row.buildingArea().compareTo(MAX_BUILDING_AREA) > 0
                        ? null : row.buildingArea(),
                row.totalFloorArea(),
                null,
                row.approvalDate(),
                null,
                LedgerDataSource.BUILDING_HUB);
    }

    /**
     * 대장 주소. 대지위치(지번)에서 끝의 「번지」를 떼어 적재가 주소 정규화에 넘기는 원문과 같은 꼴({@code 서울특별시 종로구
     * 창신동 702})로 만든다. 정규화는 수집 서비스가 한다. 대지위치가 비면 도로명대지위치를 쓴다.
     */
    private static String ledgerAddress(TitleRow row) {
        if (row.lotAddress() != null) {
            return collapse(LOT_SUFFIX.matcher(row.lotAddress()).replaceFirst(""));
        }
        return row.roadAddress() == null ? null : collapse(row.roadAddress());
    }

    private static String collapse(String text) {
        return WHITESPACES.matcher(text.strip()).replaceAll(" ");
    }

    /**
     * 표제부 한 행에서 쓰는 값. 제공처 필드명이 이 클래스 밖으로 나가지 않게 여기서 옮긴다. 빈 문자열 · 공백 한 칸은 null,
     * 0 이하 면적은 null(미기재)이다.
     */
    record TitleRow(
            String lotAddress,
            String roadAddress,
            String mainAtchCode,
            String mainPurpose,
            String structure,
            BigDecimal buildingArea,
            BigDecimal totalFloorArea,
            LocalDate approvalDate
    ) {

        static TitleRow from(JsonNode item) {
            return new TitleRow(
                    text(item, "platPlc"),
                    text(item, "newPlatPlc"),
                    text(item, "mainAtchGbCd"),
                    text(item, "mainPurpsCdNm"),
                    text(item, "strctCdNm"),
                    area(item, "archArea"),
                    area(item, "totArea"),
                    date(item, "useAprDay"));
        }

        private static String text(JsonNode item, String field) {
            String value = item.path(field).asString("");
            return value.isBlank() ? null : value.strip();
        }

        private static BigDecimal area(JsonNode item, String field) {
            JsonNode node = item.path(field);
            BigDecimal value;
            if (node.isNumber()) {
                value = node.decimalValue();
            } else {
                String raw = text(item, field);
                if (raw == null) {
                    return null;
                }
                try {
                    value = new BigDecimal(raw);
                } catch (NumberFormatException cause) {
                    return null;
                }
            }
            return value.signum() > 0 ? value.setScale(AREA_SCALE, RoundingMode.HALF_UP) : null;
        }

        private static LocalDate date(JsonNode item, String field) {
            String raw = text(item, field);
            if (raw == null) {
                return null;
            }
            try {
                return LocalDate.parse(raw, APPROVAL_DAY);
            } catch (DateTimeParseException cause) {
                return null;
            }
        }
    }
}
