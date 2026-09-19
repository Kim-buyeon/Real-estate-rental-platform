package com.duri.rentalplatform.external.mail;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.config.ExternalApiProperties;
import com.duri.rentalplatform.external.FaultInjection;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 Fault 구현. 설정한 장애를 일으킨 뒤, 정상 모드면 보내지 않고 끝난다.
 *
 * <p>정상 경로에서 Mock 에 위임하지 않는다 — Mock 은 링크(토큰 원문)를 로그에 남긴다. Fault 는 서킷 확인용으로 운영에 가까운
 * 환경에서 띄울 수 있으므로 원문을 남기지 않는다.
 *
 * <p><b>Real 과 같은 격리를 건다.</b> 인스턴스 이름은 인터페이스 상수이고 폴백은 예외를 던진다. 재시도는 Real 처럼 없다.
 *
 * <p>설정은 {@code external.mail.*} 에서 읽어 {@link FaultInjection} 이 받는 모양으로 만든다. 메일은 HTTP 연동이 아니라
 * {@link ExternalApiProperties} 의 대상(기본 URL · 키 · RestClient)에 넣지 않는다. timeout 모드의 기준은 SMTP 읽기 타임아웃
 * ({@code spring.mail.properties.mail.smtp.timeout})과 같은 값이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external.mail", name = "mode", havingValue = "fault")
public class FaultMailClient implements MailClient {

    private final ExternalApiProperties.ClientSettings settings;

    public FaultMailClient(
            @Value("${external.mail.read-timeout}") Duration readTimeout,
            @Value("${external.mail.fault.kind}") String kind,
            @Value("${external.mail.fault.delay}") Duration delay) {
        this.settings = new ExternalApiProperties.ClientSettings(
                "fault", null, null, null, readTimeout, new ExternalApiProperties.FaultSettings(kind, delay));
    }

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public void sendPasswordResetLink(String recipient, String link) {
        FaultInjection.inject(settings);
    }

    /** 폴백. Real 과 같이 원인을 기록하고 던진다. */
    @SuppressWarnings("unused")
    private void unavailable(String recipient, String link, Throwable cause) {
        // 던지는 예외에는 원인이 실리지 않으므로 여기서 남긴다. 인증 실패 · 접속 거부 · 타임아웃 · 서킷 열림(CallNotPermittedException)을
        // 가르는 단서다. 원인 예외의 메시지에는 링크가 없다. link · 본문은 인자로 넘기지 않는다 — 토큰 원문이 들어 있다.
        log.warn("메일 발송 실패 cause={}: {}", cause.getClass().getName(), cause.getMessage());
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
