package com.duri.rentalplatform.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 연동용 {@link RestClient} 구성.
 *
 * <p>연동 대상마다 별도 인스턴스를 만든다. 하나를 공유하면 한 제공처의 느린 응답에 맞춘 타임아웃이
 * 다른 제공처에도 걸린다.
 *
 * <p>재시도 · 서킷 · 폴백은 Resilience4j 애노테이션이 담당한다 — {@code application.yml} 의
 * {@code resilience4j} 절. 여기서는 타임아웃만 건다. 타임아웃은 커넥션 수준이라 라이브러리보다
 * 아래에 있어야 한다.
 */
@Configuration
@EnableConfigurationProperties(ExternalApiProperties.class)
public class ExternalClientConfig {

    @Bean
    public RestClient rentTransactionRestClient(ExternalApiProperties properties) {
        return build(properties.rentTransaction());
    }

    @Bean
    public RestClient addressNormalizeRestClient(ExternalApiProperties properties) {
        return build(properties.addressNormalize());
    }

    @Bean
    public RestClient geocodeRestClient(ExternalApiProperties properties) {
        return build(properties.geocode());
    }

    private RestClient build(ExternalApiProperties.ClientSettings settings) {
        return RestClient.builder()
                .baseUrl(settings.baseUrl())
                .requestFactory(requestFactory(settings.connectTimeout(), settings.readTimeout()))
                .build();
    }

    private ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
