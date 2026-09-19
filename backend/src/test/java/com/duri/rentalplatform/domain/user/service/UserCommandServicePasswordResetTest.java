package com.duri.rentalplatform.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetConfirmRequest;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetRequest;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import com.duri.rentalplatform.domain.user.sender.PasswordResetMailSender;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.domain.user.store.RefreshTokenStore;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link UserCommandService} 의 비밀번호 재설정(USER-06) — API 명세(회원) 1.3.
 *
 * <p>요청은 결과를 돌려주지 않으므로 무엇이 <b>일어나지 않았는지</b>로 확인한다 — 미가입 · 소셜 전용 · 간격 안이면 토큰도 메일도 없다.
 * 간격 · 발급 순서와 실패 격리는 비동기 발송자({@code PasswordResetMailSenderTest})가, 이전 토큰 무효 · 일회성 · 해시 저장은
 * 보관소 통합 테스트({@code PasswordResetTokenStoreTest})가 본다.
 * 비밀번호 규칙 위반 시 토큰을 소비하지 않는 것은 검증이 서비스 앞에서 끝나는 구조라 컨트롤러 슬라이스
 * ({@code AuthControllerPasswordResetTest})가 본다.
 */
class UserCommandServicePasswordResetTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "user@example.com";
    private static final String TOKEN = "Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl";
    private static final String NEW_PASSWORD = "N3wP@ssw0rd!";

    private UserAuthRepository userAuthRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenStore refreshTokenStore;
    private PasswordResetTokenStore tokenStore;
    private PasswordResetMailSender mailSender;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        userAuthRepository = mock(UserAuthRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenStore = mock(RefreshTokenStore.class);
        tokenStore = mock(PasswordResetTokenStore.class);
        mailSender = mock(PasswordResetMailSender.class);
        service = new UserCommandService(mock(UserRepository.class), userAuthRepository, passwordEncoder,
                mock(JwtTokenProvider.class), refreshTokenStore, tokenStore, mailSender);
    }

    @Test
    @DisplayName("요청 — 비밀번호 계정이면 회원 식별자와 이메일만 비동기 발송자에 넘기고, 요청 스레드는 Redis 를 건드리지 않는다")
    void requestHandsOffToSenderWithoutTouchingRedis() {
        when(userAuthRepository.findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL))
                .thenReturn(Optional.of(emailAuth("old-hash")));

        service.requestPasswordReset(new PasswordResetRequest(EMAIL));

        verify(mailSender).send(USER_ID, EMAIL);
        // 간격 · 발급이 요청 스레드에 있으면 계정이 있는 요청만 Redis 왕복만큼 느려지고, Redis 장애 때 그 요청만 500 이 된다.
        verifyNoInteractions(tokenStore);
    }

    @Test
    @DisplayName("요청 — 가입되지 않은 이메일이면 예외 없이 끝나고 토큰도 메일도 없다")
    void requestForUnknownEmailDoesNothing() {
        when(userAuthRepository.findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL)).thenReturn(Optional.empty());

        service.requestPasswordReset(new PasswordResetRequest(EMAIL));

        verifyNoInteractions(tokenStore, mailSender);
    }

    @Test
    @DisplayName("요청 — 소셜 전용 계정이면 이메일 인증 수단만 찾으므로 토큰도 메일도 없다")
    void requestForSocialOnlyAccountDoesNothing() {
        // 이메일 인증 수단은 없고, 이메일 외의 인증 수단으로 물으면 계정이 나오게 둔다. 서비스가 인증 수단을 가리지 않고 찾으면
        // 소셜 계정에 메일이 나간다.
        UserAuth socialAuth = mock(UserAuth.class);
        when(userAuthRepository.findByAuthTypeAndProviderId(any(AuthType.class), eq(EMAIL)))
                .thenAnswer(invocation -> invocation.getArgument(0) == AuthType.EMAIL
                        ? Optional.empty()
                        : Optional.of(socialAuth));

        service.requestPasswordReset(new PasswordResetRequest(EMAIL));

        verify(userAuthRepository).findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL);
        verifyNoInteractions(tokenStore, mailSender, socialAuth);
    }

    @Test
    @DisplayName("확정 — 토큰의 회원 비밀번호를 인코딩해 바꾸고 그 회원의 리프레시 토큰을 폐기한다")
    void confirmChangesPasswordAndRevokesRefreshToken() {
        UserAuth userAuth = emailAuth("old-hash");
        when(tokenStore.consume(TOKEN)).thenReturn(Optional.of(USER_ID));
        when(userAuthRepository.findByUserUserIdAndAuthType(USER_ID, AuthType.EMAIL))
                .thenReturn(Optional.of(userAuth));
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");

        service.confirmPasswordReset(new PasswordResetConfirmRequest(TOKEN, NEW_PASSWORD));

        assertThat(userAuth.getPasswordHash()).isEqualTo("new-hash");
        verify(refreshTokenStore).delete(USER_ID);
    }

    @Test
    @DisplayName("확정 — 토큰이 무효(없음 · 만료 · 사용됨 · 대체됨)면 AUTH_RESET_TOKEN_INVALID 이고 아무것도 바꾸지 않는다")
    void confirmWithInvalidTokenFails() {
        when(tokenStore.consume(TOKEN)).thenReturn(Optional.empty());

        assertInvalidToken(() -> service.confirmPasswordReset(new PasswordResetConfirmRequest(TOKEN, NEW_PASSWORD)));

        verifyNoInteractions(userAuthRepository, passwordEncoder, refreshTokenStore);
    }

    @Test
    @DisplayName("확정 — 토큰의 회원에게 이메일 인증 수단이 없으면 무효와 같게 AUTH_RESET_TOKEN_INVALID")
    void confirmWithoutEmailAuthFails() {
        when(tokenStore.consume(TOKEN)).thenReturn(Optional.of(USER_ID));
        when(userAuthRepository.findByUserUserIdAndAuthType(USER_ID, AuthType.EMAIL)).thenReturn(Optional.empty());

        assertInvalidToken(() -> service.confirmPasswordReset(new PasswordResetConfirmRequest(TOKEN, NEW_PASSWORD)));

        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(refreshTokenStore);
    }

    private static void assertInvalidToken(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID));
    }

    /** 식별자가 채워진 회원의 이메일 인증 수단. 식별자는 저장 시점에 생기므로 반사로 넣는다. */
    private static UserAuth emailAuth(String passwordHash) {
        User user = User.signUpWithEmail("홍길동", EMAIL, null);
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        return UserAuth.signUpWithEmail(user, EMAIL, passwordHash);
    }
}
