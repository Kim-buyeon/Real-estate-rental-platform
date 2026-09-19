package com.duri.rentalplatform.domain.user.sender;

import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.external.mail.MailClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정의 계정 확인 뒤 단계 — 발송 간격 · 토큰 발급 · 링크 발송 — 를 요청 스레드 밖에서 한다. API 명세(회원) 1.3.
 *
 * <p><b>비동기</b> — 이 단계는 계정이 있을 때만 일어난다. 요청 스레드에서 하면 Redis 왕복 · SMTP 왕복만큼 계정이 있는 요청만
 * 느려지고, Redis 장애 때 그 요청만 500 이 되어 가입 여부가 드러난다. 여기로 옮겨 요청 스레드는 두 경로 모두 DB 조회 한 번으로
 * 끝난다. 비동기 실행기는 설정(비동기 실행)이 정하고, 프록시가 걸도록 별도 빈의 public 메서드로 둔다({@code NotificationDispatcher}
 * 와 같다).
 *
 * <p>순서는 간격 → 발급 → 발송이다. 간격을 발급보다 먼저 잡아야 간격 안의 재요청이 방금 보낸 메일의 토큰을 무효로 만들지 않는다.
 *
 * <p><b>격리</b> — Redis · 메일 실패는 기록하고 삼킨다. 응답은 이미 나갔고, 사용자는 간격 뒤 다시 요청하면 된다. 기록에는 예외
 * (클래스 · 메시지)를 남기되 토큰 · 링크 · 본문은 남기지 않는다 — 토큰 원문이 들어 있다.
 */
@Slf4j
@Component
public class PasswordResetMailSender {

    /** 화면 경로 — 명세 1.3. 토큰은 URL 안전 Base64 라 인코딩하지 않고 붙인다. */
    static final String CONFIRM_PATH = "/password-reset/confirm?token=";

    private final PasswordResetTokenStore tokenStore;
    private final MailClient mailClient;
    private final String linkBaseUrl;

    public PasswordResetMailSender(
            PasswordResetTokenStore tokenStore,
            MailClient mailClient,
            @Value("${password-reset.link-base-url}") String linkBaseUrl) {
        this.tokenStore = tokenStore;
        this.mailClient = mailClient;
        // 끝의 / 를 떼어 둔다. 환경 변수에 https://example.com/ 처럼 넣으면 경로가 // 로 이어진다.
        this.linkBaseUrl = linkBaseUrl.endsWith("/")
                ? linkBaseUrl.substring(0, linkBaseUrl.length() - 1)
                : linkBaseUrl;
    }

    /**
     * 간격 안이면 아무것도 하지 않고, 아니면 새 토큰을 발급해(이전 토큰은 무효) 링크를 보낸다.
     *
     * @param userId    비밀번호 계정이 확인된 회원
     * @param recipient 그 계정의 이메일
     */
    @Async
    public void send(Long userId, String recipient) {
        try {
            if (!tokenStore.claimSendSlot(userId)) {
                log.debug("비밀번호 재설정 발송 간격 안 — 건너뜀 userId={}", userId);
                return;
            }
            String token = tokenStore.issue(userId);
            mailClient.sendPasswordResetLink(recipient, linkOf(token));
        } catch (RuntimeException e) {
            // 예외를 인자로 넘겨 클래스 · 메시지 · 원인 사슬을 남긴다. Redis · SMTP 예외 메시지에는 토큰이 없다.
            log.warn("비밀번호 재설정 메일 처리 실패 — 응답은 이미 나갔다. userId={}", userId, e);
        }
    }

    String linkOf(String token) {
        return linkBaseUrl + CONFIRM_PATH + token;
    }
}
