package com.duri.rentalplatform.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 연동 대상별 설정. 키는 {@code .env} 에서 환경 변수로 들어온다 — 값을 코드에 적지 않는다.
 *
 * <p>대상마다 세 구현(Mock · Real · Fault) 중 하나가 뜨며, 무엇이 뜨는지는 {@code mode} 가 정한다.
 * 기본값은 {@code mock} 이다. 기본을 real 로 두면 키가 없는 환경에서 기동이 외부 호출로 새고,
 * 승인 전 서비스를 부르게 된다.
 *
 * @param rentTransaction  국토교통부 전월세 실거래가
 * @param addressNormalize 도로명주소 주소 정규화
 * @param geocode          카카오 로컬 좌표 변환
 */
@ConfigurationProperties(prefix = "external")
public record ExternalApiProperties(
        ClientSettings rentTransaction,
        ClientSettings addressNormalize,
        ClientSettings geocode
) {

    /**
     * 연동 하나의 설정.
     *
     * <p><b>타임아웃 값은 미확정이다.</b> 어느 설계 문서도 대상별 연결 · 읽기 타임아웃을 정하지 않았다.
     * application.yml 에 둔 값은 잠정치이며, 예비 부하 측정에서 확정되면 그 값으로 바꾼다.
     *
     * @param mode           mock · real · fault 중 하나
     * @param baseUrl        제공처 기본 URL
     * @param apiKey         인증키. 환경 변수에서 주입한다. 로그에 남기지 않는다
     * @param connectTimeout 연결 타임아웃 (미확정)
     * @param readTimeout    읽기 타임아웃 (미확정)
     * @param fault          Fault 구현이 쓰는 주입 설정
     */
    public record ClientSettings(
            String mode,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            FaultSettings fault
    ) {
        public ClientSettings {
            mode = mode == null ? "mock" : mode;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            fault = fault == null ? new FaultSettings(null, null) : fault;
        }
    }

    /**
     * 장애 주입 설정. Fault 구현이 읽는다.
     *
     * @param kind  {@code error}(즉시 실패) · {@code delay}(지연 후 정상) · {@code timeout}(지연 후 실패)
     * @param delay 지연 시간
     */
    public record FaultSettings(String kind, Duration delay) {
        public FaultSettings {
            kind = kind == null ? "error" : kind;
            delay = delay == null ? Duration.ofSeconds(10) : delay;
        }
    }
}
