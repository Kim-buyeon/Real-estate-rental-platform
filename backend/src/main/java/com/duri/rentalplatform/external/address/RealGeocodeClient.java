package com.duri.rentalplatform.external.address;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 주소 검색(좌표 변환) 실제 호출 구현.
 *
 * <p>제공처 문서 — https://developers.kakao.com/docs/latest/ko/local/dev-guide (주소 검색하기).
 * {@code GET /v2/local/search/address.json?query=...} 이며 인증은 헤더
 * {@code Authorization: KakaoAK {REST_API_KEY}} 다. 쿼리 파라미터가 아니라 헤더이므로 키가 URL 과
 * 접근 로그에 남지 않는다.
 *
 * <p>응답의 {@code x} 가 경도, {@code y} 가 위도다. 순서를 뒤집으면 서울 매물이 중국 앞바다에 찍힌다.
 * 문자열로 오므로 {@link BigDecimal} 로 그대로 받는다 — double 로 받으면 저장 시 자릿수가 흔들린다.
 */
@Component
@ConditionalOnProperty(prefix = "external.geocode", name = "mode", havingValue = "real")
public class RealGeocodeClient implements GeocodeClient {

    private static final String DATA_SOURCE = "KAKAO_LOCAL";
    private static final String AUTHORIZATION_PREFIX = "KakaoAK ";

    private final RestClient restClient;
    private final ExternalApiProperties.ClientSettings settings;

    public RealGeocodeClient(
            @Qualifier("geocodeRestClient") RestClient restClient,
            ExternalApiProperties properties) {
        this.restClient = restClient;
        this.settings = properties.geocode();
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public Optional<Coordinates> geocode(String address) {
        KakaoAddressResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/address.json")
                        .queryParam("query", address)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION_PREFIX + settings.apiKey())
                .retrieve()
                .body(KakaoAddressResponse.class);

        if (response == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }
        List<KakaoAddressResponse.Document> documents = response.documents();
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        KakaoAddressResponse.Document document = documents.getFirst();
        if (document.x() == null || document.y() == null) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates(document.y(), document.x(), DATA_SOURCE));
    }

    /**
     * 서킷이 열렸거나 재시도가 모두 실패했을 때의 폴백.
     *
     * <p>좌표를 지어내지 않는다. 임의 좌표를 넣으면 지도에 존재하지 않는 매물이 찍히고, 그것이
     * 반경 검색 결과에 섞인다.
     */
    @SuppressWarnings("unused")
    private Optional<Coordinates> unavailable(String address, Throwable cause) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }

    /** 제공처 응답 형태. 우리 DTO({@link Coordinates})와 분리해 둔다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAddressResponse(List<Document> documents) {

        /**
         * 좌표만 받는다. 카카오가 함께 주는 주소 문자열은 쓰지 않는다 — 주소 정규화는 도로명주소
         * 연동의 몫이고, 두 곳에서 받으면 어느 쪽이 저장되었는지 추적이 어려워진다.
         *
         * @param x 경도
         * @param y 위도
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Document(BigDecimal x, BigDecimal y) {
        }
    }
}
