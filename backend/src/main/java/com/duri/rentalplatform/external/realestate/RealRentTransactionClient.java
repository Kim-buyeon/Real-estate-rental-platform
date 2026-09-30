package com.duri.rentalplatform.external.realestate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 국토교통부 전월세 실거래가 실제 호출 구현.
 *
 * <p>제공처 문서 — 아파트 전월세 https://www.data.go.kr/data/15126474/openapi.do,
 * 오피스텔 전월세 https://www.data.go.kr/data/15126475/openapi.do.
 * 두 서비스는 인증키가 같고(DATA_GO_KR_API_KEY) <b>활용 신청은 각각</b>이다.
 *
 * <p>요청 파라미터는 {@code serviceKey} · {@code LAWD_CD}(법정동 코드 앞 5자리) ·
 * {@code DEAL_YMD}(계약년월 YYYYMM) · {@code pageNo} · {@code numOfRows} 다.
 * 응답은 XML 이며 JSON 옵션이 없다. XML 파서를 따로 들이지 않고 JDK 내장 DOM 으로 읽는다 — 읽기는 매매 Real 과
 * 함께 쓰는 {@link RtmsXmlSupport} 가 맡는다.
 *
 * <p>인증키는 포털의 <b>인코딩 키</b>를 {@code .env} 에 그대로 넣는다. 이 클래스는 키를 다시 인코딩하지
 * 않는다 — {@link #request} 참고.
 *
 * <p>호출 로깅은 이 클래스에 쓰지 않는다. 「외부 API 호출 로깅」은 관점의 몫이며(횡단 관심사 설계서 1.1),
 * 포인트컷 애노테이션은 아직 만들어지지 않았다. 그 기반 작업이 끝나면 애노테이션만 붙인다.
 */
@Component
@ConditionalOnProperty(prefix = "external.rent-transaction", name = "mode", havingValue = "real")
public class RealRentTransactionClient implements RentTransactionClient {

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealRentTransactionClient(
            @Qualifier("rentTransactionRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.rentTransaction();
        // 인코딩 키가 아니면 기동에서 멈춘다 — 사유는 RtmsXmlSupport.verifyEncodedKey.
        RtmsXmlSupport.verifyEncodedKey(settings.apiKey(), "external.rent-transaction.api-key");
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
        List<RentTransaction> collected = new ArrayList<>();
        int receivedItems = 0;
        for (int pageNo = 1; pageNo <= RtmsXmlSupport.MAX_PAGES; pageNo++) {
            Document document = RtmsXmlSupport.parse(request(query, pageNo));
            RtmsXmlSupport.verifyResultCode(document);

            collected.addAll(RtmsXmlSupport.readItems(document, item -> toTransaction(item, query)));

            // 종료는 버린 건까지 센 항목 수로 판단한다 — RtmsXmlSupport.itemCount.
            int pageItems = RtmsXmlSupport.itemCount(document);
            receivedItems += pageItems;
            if (pageItems == 0 || receivedItems >= RtmsXmlSupport.totalCount(document)) {
                break;
            }
        }
        return collected;
    }

    /**
     * 서킷이 열려 있거나 재시도가 모두 실패했을 때의 폴백.
     *
     * <p>빈 목록을 돌려주지 않는다. 「해당 월에 거래가 없었다」와 「조회하지 못했다」가 같은 값이 되면
     * 적재가 실패를 성공으로 기록하고, 그 달 매물 후보가 빠진 채 적재가 성공으로 끝난다.
     */
    @SuppressWarnings("unused")
    private List<RentTransaction> unavailable(RentTransactionQuery query, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    /**
     * 요청 URI 를 <b>이미 인코딩된 것으로</b> 조립한다({@code build(true)}).
     *
     * <p>포털이 발급하는 인코딩 키({@code %2B} · {@code %3D} 포함)를 그대로 보낸다. RestClient 의 URI
     * 빌더에 맡기면 {@code %} 가 {@code %25} 로 한 번 더 인코딩되어 「등록되지 않은 서비스키」(403)가
     * 난다. 나머지 값은 서비스 경로 · 숫자뿐이라 인코딩할 문자가 없다.
     */
    private byte[] request(RentTransactionQuery query, int pageNo) {
        RentBuildingType type = query.buildingType();
        URI uri = UriComponentsBuilder.fromUriString(settings.baseUrl())
                .pathSegment(type.getServicePath(), type.getOperation())
                .queryParam("serviceKey", settings.apiKey())
                .queryParam("LAWD_CD", query.lawdCode())
                .queryParam("DEAL_YMD", query.contractYearMonth().format(RtmsXmlSupport.DEAL_YMD))
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", RtmsXmlSupport.ROWS_PER_PAGE)
                .build(true)
                .toUri();
        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
    }

    /**
     * 한 건을 우리 형태로 옮긴다. 필수 값이 비어 있으면 {@code null} — 그 건만 버린다.
     */
    private RentTransaction toTransaction(Element item, RentTransactionQuery query) {
        BigDecimal areaSqm = RtmsXmlSupport.readDecimal(item, "excluUseAr");
        Long deposit = RtmsXmlSupport.readAmount(item, "deposit");
        LocalDate contractDate = RtmsXmlSupport.readDealDate(item);
        if (areaSqm == null || deposit == null || contractDate == null) {
            return null;
        }
        Long monthlyRent = RtmsXmlSupport.readAmount(item, "monthlyRent");
        // 아파트 서비스는 aptNm, 오피스텔 서비스는 offiNm 으로 건물명을 준다.
        String buildingName = RtmsXmlSupport.firstNonBlank(
                RtmsXmlSupport.readText(item, "aptNm"), RtmsXmlSupport.readText(item, "offiNm"));

        return new RentTransaction(
                query.lawdCode(),
                RtmsXmlSupport.readText(item, "umdNm"),
                buildingName,
                RtmsXmlSupport.readText(item, "jibun"),
                areaSqm,
                RtmsXmlSupport.readInteger(item, "floor"),
                deposit,
                monthlyRent == null ? 0L : monthlyRent,
                contractDate,
                RtmsXmlSupport.readInteger(item, "buildYear"),
                query.buildingType(),
                query.buildingType().getDataSource());
    }
}
