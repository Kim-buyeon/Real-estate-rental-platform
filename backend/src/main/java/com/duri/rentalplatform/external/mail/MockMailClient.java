package com.duri.rentalplatform.external.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 Mock. 보내지 않고 링크를 로그로 남긴다 — 로컬에서 SMTP 계정 없이 요청 → 링크 → 확정을 따라갈 수 있게 한다.
 *
 * <p><b>토큰 원문이 로그에 남는 것은 이 구현에서만이다.</b> 로그를 읽을 수 있으면 그 계정의 비밀번호를 바꿀 수 있으므로, 운영에서는
 * {@code external.mail.mode=real} 로 띄운다. Real · Fault 는 링크를 로그에 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external.mail", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockMailClient implements MailClient {

    @Override
    public void sendPasswordResetLink(String recipient, String link) {
        log.info("[Mock 메일] 비밀번호 재설정 링크 to={} link={}", recipient, link);
    }
}
