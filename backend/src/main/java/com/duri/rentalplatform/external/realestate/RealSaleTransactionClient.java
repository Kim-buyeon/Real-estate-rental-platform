package com.duri.rentalplatform.external.realestate;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.ExternalFallbackLog;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 국토교통부 매매 실거래가 실제 호출 구현. 서비스 경로는 {@link SaleBuildingType} 이 갖는다.
 *
 * <p>요청 파라미터 · 응답 골격 · 페이징은 전월세({@link RealRentTransactionClient})와 같다 — {@code serviceKey} ·
 * {@code LAWD_CD} · {@code DEAL_YMD} · {@code pageNo} · {@code numOfRows}, XML 응답. 읽기는 {@link RtmsXmlSupport} 가 맡는다.
 *
 * <p>항목에서 쓰는 필드(2026-09-30 종로구 202608 실호출 응답으로 확인):
 * <ul>
 *   <li>{@code aptNm}(아파트) · {@code offiNm}(오피스텔) — 건물명</li>
 *   <li>{@code umdNm} · {@code jibun} · {@code excluUseAr} · {@code floor} · {@code buildYear}</li>
 *   <li>{@code dealAmount} — 거래금액. 만원 단위 · 천 단위 콤마({@code 172,500})</li>
 *   <li>{@code dealYear} · {@code dealMonth} · {@code dealDay} — 계약일</li>
 *   <li>{@code cdealType} — 해제 여부. 해제되지 않은 거래는 공백 한 칸으로 온다. 값이 차 있으면 해제 거래로 본다</li>
 * </ul>
 *
 * <p>인증키는 전월세와 같은 {@code DATA_GO_KR_API_KEY} 이며 포털의 <b>인코딩 키</b>를 그대로 넣는다. 이 클래스는 키를 다시
 * 인코딩하지 않는다 — {@link #request} 참고.
 *
 * <p>호출 로깅은 이 클래스에 쓰지 않는다 — 전월세 Real 과 같은 이유(횡단 관심사 설계서 1.1).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external.sale-transaction", name = "mode", havingValue = "real")
public class RealSaleTransactionClient implements SaleTransactionClient {

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealSaleTransactionClient(
            @Qualifier("saleTransactionRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.saleTransaction();
        // 인코딩 키가 아니면 기동에서 멈춘다 — 사유는 RtmsXmlSupport.verifyEncodedKey.
        RtmsXmlSupport.verifyEncodedKey(settings.apiKey(), "external.sale-transaction.api-key");
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<SaleTransaction> findSaleTransactions(SaleTransactionQuery query) {
        List<SaleTransaction> collected = new ArrayList<>();
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
     * <p>빈 목록을 돌려주지 않는다. 「그 달 매매가 없었다」와 「조회하지 못했다」가 같은 값이 되면 그 달 표본이 빠진 채 시세
     * 중앙값이 산출되고, 갱신 배치는 덜 모은 표본의 값으로 멀쩡한 시세를 덮는다. 시세는 깡통전세 판정(RISK-02) 기준금액의
     * 밑값이자 전세가율의 분모이므로 폴백이 값을 지어내면 판정이 조용히 틀어진다.
     */
    @SuppressWarnings("unused")
    private List<SaleTransaction> unavailable(SaleTransactionQuery query, Throwable cause) {
        ExternalFallbackLog.warn(log, "매매 실거래가", cause);
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    /**
     * 요청 URI 를 <b>이미 인코딩된 것으로</b> 조립한다({@code build(true)}). 인코딩 키를 RestClient 의 URI 빌더에 맡기면
     * {@code %} 가 {@code %25} 로 한 번 더 인코딩되어 403 이 난다 — 전월세 Real 과 같다.
     */
    private byte[] request(SaleTransactionQuery query, int pageNo) {
        SaleBuildingType type = query.buildingType();
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
     * 한 건을 우리 형태로 옮긴다. 필수 값(전용면적 · 거래금액 · 계약일)이 비어 있으면 {@code null} — 그 건만 버린다.
     */
    private SaleTransaction toTransaction(Element item, SaleTransactionQuery query) {
        BigDecimal areaSqm = RtmsXmlSupport.readDecimal(item, "excluUseAr");
        Long dealAmount = RtmsXmlSupport.readAmount(item, "dealAmount");
        LocalDate contractDate = RtmsXmlSupport.readDealDate(item);
        if (areaSqm == null || dealAmount == null || contractDate == null) {
            return null;
        }
        String buildingName = RtmsXmlSupport.firstNonBlank(
                RtmsXmlSupport.readText(item, "aptNm"), RtmsXmlSupport.readText(item, "offiNm"));
        String cancelType = RtmsXmlSupport.readText(item, "cdealType");

        return new SaleTransaction(
                query.lawdCode(),
                RtmsXmlSupport.readText(item, "umdNm"),
                buildingName,
                RtmsXmlSupport.readText(item, "jibun"),
                areaSqm,
                RtmsXmlSupport.readInteger(item, "floor"),
                dealAmount,
                contractDate,
                RtmsXmlSupport.readInteger(item, "buildYear"),
                cancelType != null && !cancelType.isBlank(),
                query.buildingType(),
                query.buildingType().getDataSource());
    }
}
