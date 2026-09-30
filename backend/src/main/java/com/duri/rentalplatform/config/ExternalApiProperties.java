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
 * @param buildingLedger   국토교통부 건축물대장. Real 이 없어 기본 URL · 키 · 타임아웃은 Fault 의 timeout 모드만 쓴다
 * @param saleTransaction  국토교통부 매매 실거래가. 시세 표본이다. 전월세와 같은 제공처 · 같은 키지만 서킷은 따로 둔다
 */
@ConfigurationProperties(prefix = "external")
public record ExternalApiProperties(
        ClientSettings rentTransaction,
        ClientSettings addressNormalize,
        ClientSettings geocode,
        ClientSettings buildingLedger,
        ClientSettings saleTransaction
) {

    /**
     * 전월세가 real 인데 매매가 real 이 아니면 기동을 멈춘다.
     *
     * <p>매물은 실거래로 들어오는데 시세 표본이 Mock 이면, Mock 의 법정동명이 실매물과 맞지 않아 자치구 표본으로 넓혀지고
     * 실매물 전체의 시세가 Mock 값으로 덮인다. 갱신 배치가 그 값으로 재분석 · 알림까지 이어 간다 — 등기 외 가짜 데이터를
     * 쓰지 않는다는 규칙(#301)을 조용히 깨는 조합이라 설정 단계에서 막는다.
     */
    public ExternalApiProperties {
        if (rentTransaction != null && saleTransaction != null
                && "real".equals(rentTransaction.mode()) && !"real".equals(saleTransaction.mode())) {
            throw new IllegalStateException(
                    "external.rent-transaction.mode=real 이면 external.sale-transaction.mode 도 real 이어야 한다 — 현재 "
                            + saleTransaction.mode());
        }
    }

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
