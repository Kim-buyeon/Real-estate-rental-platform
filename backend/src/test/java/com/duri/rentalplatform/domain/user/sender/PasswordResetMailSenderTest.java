package com.duri.rentalplatform.domain.user.sender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.external.mail.MailClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * {@link PasswordResetMailSender} — 계정 확인 뒤 단계(간격 → 발급 → 발송)의 순서, 링크가 명세 1.3 의 화면 경로와 같은지, Redis ·
 * 메일 실패가 삼켜지는지. 비동기 실행은 프록시가 거는 것이라 여기서는 메서드를 직접 부른다.
 */
class PasswordResetMailSenderTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "user@example.com";
    private static final String TOKEN = "Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl";

    private PasswordResetTokenStore tokenStore;
    private MailClient mailClient;

    @BeforeEach
    void setUp() {
        tokenStore = mock(PasswordResetTokenStore.class);
        mailClient = mock(MailClient.class);
    }

    @Test
    @DisplayName("간격을 잡고 토큰을 발급해 <기준 주소>/password-reset/confirm?token=<토큰> 링크를 보낸다")
    void claimsIssuesAndSendsSpecLink() {
        when(tokenStore.claimSendSlot(USER_ID)).thenReturn(true);
        when(tokenStore.issue(USER_ID)).thenReturn(TOKEN);

        senderWithBase("https://example.com").send(USER_ID, EMAIL);

        InOrder order = inOrder(tokenStore, mailClient);
        order.verify(tokenStore).claimSendSlot(USER_ID);
        order.verify(tokenStore).issue(USER_ID);
        order.verify(mailClient).sendPasswordResetLink(
                EMAIL, "https://example.com/password-reset/confirm?token=" + TOKEN);
    }

    @Test
    @DisplayName("기준 주소 끝의 / 는 한 번만 남는다")
    void trimsTrailingSlash() {
        when(tokenStore.claimSendSlot(USER_ID)).thenReturn(true);
        when(tokenStore.issue(USER_ID)).thenReturn(TOKEN);

        senderWithBase("https://example.com/").send(USER_ID, EMAIL);

        verify(mailClient).sendPasswordResetLink(
                EMAIL, "https://example.com/password-reset/confirm?token=" + TOKEN);
    }

    @Test
    @DisplayName("발송 간격 안이면 새 토큰을 발급하지 않는다 — 방금 보낸 메일의 토큰이 살아 있다")
    void withinIntervalDoesNotIssue() {
        when(tokenStore.claimSendSlot(USER_ID)).thenReturn(false);

        senderWithBase("https://example.com").send(USER_ID, EMAIL);

        verify(tokenStore, never()).issue(anyLong());
        verifyNoInteractions(mailClient);
    }

    @Test
    @DisplayName("Redis 보관소가 예외를 던져도 삼키고 메일을 보내지 않는다 — 응답은 이미 나갔다")
    void swallowsStoreFailure() {
        when(tokenStore.claimSendSlot(USER_ID))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThatCode(() -> senderWithBase("https://example.com").send(USER_ID, EMAIL))
                .doesNotThrowAnyException();
        verifyNoInteractions(mailClient);
    }

    @Test
    @DisplayName("메일 발송 실패는 기록하고 삼킨다")
    void swallowsSendFailure() {
        when(tokenStore.claimSendSlot(USER_ID)).thenReturn(true);
        when(tokenStore.issue(USER_ID)).thenReturn(TOKEN);
        doThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE))
                .when(mailClient).sendPasswordResetLink(anyString(), anyString());

        assertThatCode(() -> senderWithBase("https://example.com").send(USER_ID, EMAIL))
                .doesNotThrowAnyException();
    }

    private PasswordResetMailSender senderWithBase(String linkBaseUrl) {
        return new PasswordResetMailSender(tokenStore, mailClient, linkBaseUrl);
    }
}
