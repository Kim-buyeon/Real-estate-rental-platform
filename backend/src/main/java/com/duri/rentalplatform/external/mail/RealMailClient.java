package com.duri.rentalplatform.external.mail;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP 로 실제 발송하는 구현. 운영은 Gmail SMTP(앱 비밀번호)다 — 기술 스택 정의서 2장.
 *
 * <p>접속 정보(호스트 · 포트 · 계정 · 앱 비밀번호)와 타임아웃은 {@code spring.mail.*} 가 갖고 Boot 자동 구성이
 * {@link JavaMailSender} 를 만든다. 값은 환경 변수로 들어온다({@code MAIL_*}). 보내는 주소는 {@code external.mail.from}({@code MAIL_FROM}) 이다 — Gmail 은
 * 로그인 계정이나 그 계정에 등록된 별칭이 아닌 주소를 보낸 사람으로 쓰면 로그인 계정으로 바꿔 보낸다.
 *
 * <p>본문은 평문이다. 링크 하나를 싣는 메일이라 HTML 템플릿을 들이지 않는다.
 *
 * <p>SMTP 실패는 {@link MailException} 으로 오고, 서킷 폴백이 {@code EXTERNAL_API_UNAVAILABLE} 로 바꾼다. 링크(토큰 원문)는 로그에
 * 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external.mail", name = "mode", havingValue = "real")
public class RealMailClient implements MailClient {

    static final String SUBJECT = "[전월세 부동산 금융 플랫폼] 비밀번호 재설정 안내";

    private final JavaMailSender mailSender;
    private final String from;
    private final long linkValidMinutes;

    public RealMailClient(
            JavaMailSender mailSender,
            @Value("${external.mail.from}") String from,
            @Value("${password-reset.token-ttl}") Duration tokenTtl) {
        this.mailSender = mailSender;
        this.from = from;
        this.linkValidMinutes = tokenTtl.toMinutes();
    }

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public void sendPasswordResetLink(String recipient, String link) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(SUBJECT);
        message.setText(body(link, linkValidMinutes));
        mailSender.send(message);
    }

    /** 본문. 링크 수명은 토큰 수명 설정에서 읽는다 — 문구에 숫자를 박으면 설정만 바뀔 때 안내가 틀린다. */
    static String body(String link, long validMinutes) {
        return """
                비밀번호 재설정을 요청하셨습니다.
                아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 %d분 동안 한 번만 쓸 수 있습니다.

                %s

                요청하지 않으셨다면 이 메일을 무시하셔도 됩니다. 비밀번호는 바뀌지 않습니다.
                """.formatted(validMinutes, link);
    }

    /** 폴백. 보낸 것처럼 조용히 끝내지 않는다 — 원인을 기록하고 던진다. */
    @SuppressWarnings("unused")
    private void unavailable(String recipient, String link, Throwable cause) {
        // 던지는 예외에는 원인이 실리지 않으므로 여기서 남긴다. 인증 실패 · 접속 거부 · 타임아웃 · 서킷 열림(CallNotPermittedException)을
        // 가르는 단서다. 원인 예외의 메시지에는 링크가 없다. link · 본문은 인자로 넘기지 않는다 — 토큰 원문이 들어 있다.
        log.warn("메일 발송 실패 cause={}: {}", cause.getClass().getName(), cause.getMessage());
        throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE);
    }
}
