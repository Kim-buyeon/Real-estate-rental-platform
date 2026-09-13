package com.duri.rentalplatform.external.address;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 도로명주소 주소 정규화 실제 호출 구현.
 *
 * <p>제공처 문서 — https://business.juso.go.kr/addrlink/openApi/searchApi.do (도로명주소 검색 API).
 * 요청은 {@code confmKey}(승인키) · {@code keyword} · {@code currentPage} · {@code countPerPage} ·
 * {@code resultType=json} 이다.
 *
 * <p>이 API 도 오류를 HTTP 200 본문에 담아 보낸다. {@code results.common.errorCode} 가 "0" 이 아니면
 * 실패로 본다. 검색 결과 0건은 오류가 아니라 「그런 주소가 없다」이므로 빈 값으로 돌려준다.
 */
@Component
@ConditionalOnProperty(prefix = "external.address-normalize", name = "mode", havingValue = "real")
public class RealAddressNormalizeClient implements AddressNormalizeClient {

    private static final String DATA_SOURCE = "JUSO_ROAD_ADDRESS";
    private static final String SUCCESS_ERROR_CODE = "0";

    /** 첫 건만 쓴다. 적재는 실거래 자료의 지번 주소를 그대로 넣으므로 다중 후보를 고를 근거가 없다. */
    private static final int COUNT_PER_PAGE = 1;

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealAddressNormalizeClient(
            @Qualifier("addressNormalizeRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.addressNormalize();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public Optional<NormalizedAddress> normalize(String rawAddress) {
        JusoResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/addrlink/addrLinkApi.do")
                        .queryParam("confmKey", settings.apiKey())
                        .queryParam("keyword", rawAddress)
                        .queryParam("currentPage", 1)
                        .queryParam("countPerPage", COUNT_PER_PAGE)
                        .queryParam("resultType", "json")
                        .build())
                .retrieve()
                .body(JusoResponse.class);

        if (response == null || response.results() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        JusoResponse.Common common = response.results().common();
        if (common != null && !SUCCESS_ERROR_CODE.equals(common.errorCode())) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }

        List<JusoResponse.Juso> found = response.results().juso();
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        JusoResponse.Juso juso = found.getFirst();
        return Optional.of(new NormalizedAddress(
                juso.roadAddr(), juso.jibunAddr(), juso.sggNm(), juso.emdNm(), juso.zipNo(), DATA_SOURCE));
    }

    /**
     * 서킷이 열렸거나 재시도가 모두 실패했을 때의 폴백.
     *
     * <p>빈 값을 돌려주지 않는다. 빈 값은 「그런 주소가 없다」는 뜻이라 적재가 그 매물을 조용히 버리고
     * 실패 건수에도 남지 않는다.
     */
    @SuppressWarnings("unused")
    private Optional<NormalizedAddress> unavailable(String rawAddress, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    /**
     * 제공처 응답 형태. 우리 DTO({@link NormalizedAddress})와 분리해 둔다 — 외부 필드명이
     * 도메인과 API 응답으로 새어 나가지 않게 하기 위함이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record JusoResponse(Results results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Results(Common common, List<Juso> juso) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Common(String errorCode, String errorMessage, String totalCount) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Juso(String roadAddr, String jibunAddr, String zipNo, String siNm, String sggNm,
                    String emdNm) {
        }
    }
}
