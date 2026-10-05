package com.duri.rentalplatform.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.BoundedPasswordEncoder;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.LoginRequest;
import com.duri.rentalplatform.domain.user.dto.response.TokenResponse;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import com.duri.rentalplatform.domain.user.sender.PasswordResetMailSender;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.domain.user.store.RefreshTokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link UserCommandService#login} — 대조 결과별 응답과, 비밀번호 대조 · 토큰 보관이 트랜잭션(= 커넥션 점유) 밖에서 일어나는지.
 *
 * <p>트랜잭션 경계는 데이터베이스 없이 확인한다. 실제 커넥션을 열지 않는 트랜잭션 매니저를 끼워 경계의 열림만 기록하고,
 * 대조 · 보관 시점에 {@link TransactionSynchronizationManager#isActualTransactionActive()} 를 읽는다.
 * 커넥션 풀 고갈 자체(부하에서만 드러남)는 이 테스트의 범위 밖이다.
 */
class UserCommandServiceLoginTest {

    private static final long USER_ID = 7L;
    private static final long AUTH_ID = 70L;
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "P@ssw0rd!1";
    private static final String HASH = "stored-hash";

    private UserAuthRepository userAuthRepository;
    private BoundedPasswordEncoder passwordEncoder;
    private RefreshTokenStore refreshTokenStore;
    private BoundaryRecordingTransactionManager transactionManager;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        userAuthRepository = mock(UserAuthRepository.class);
        passwordEncoder = mock(BoundedPasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        refreshTokenStore = mock(RefreshTokenStore.class);
        transactionManager = new BoundaryRecordingTransactionManager();
        service = new UserCommandService(mock(UserRepository.class), userAuthRepository, passwordEncoder,
                jwtTokenProvider, refreshTokenStore, mock(PasswordResetTokenStore.class),
                mock(PasswordResetMailSender.class), transactionManager);
        when(jwtTokenProvider.createAccessToken(USER_ID, "USER")).thenReturn("access");
        when(jwtTokenProvider.createRefreshToken(USER_ID)).thenReturn("refresh");
        when(jwtTokenProvider.getAccessTokenValiditySeconds()).thenReturn(1800L);
    }

    @Test
    @DisplayName("가입되지 않은 이메일도 해시 상한이 차 있으면 가입된 이메일과 같은 503 SERVICE_BUSY 다 — 상태 코드로 가입 여부가 갈리지 않는다")
    void unknownEmailGetsServiceBusyWhenHashingSaturated() {
        when(userAuthRepository.findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL)).thenReturn(Optional.empty());
        doThrow(new BusinessException(ErrorCode.SERVICE_BUSY)).when(passwordEncoder).awaitCapacity();

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_BUSY));

        verify(passwordEncoder, never()).matches(any(), any());
        verifyNoInteractions(refreshTokenStore);
        assertThat(transactionManager.writeBoundaries).isZero();
    }

    @Test
    @DisplayName("성공 — 토큰을 돌려주고 최종 로그인 시각을 갱신하고 리프레시 토큰을 보관한다")
    void successIssuesTokensUpdatesLastLoginAndStoresRefreshToken() {
        UserAuth userAuth = stubEmailAuth();
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        assertThat(userAuth.getLastLoginAt()).isNull();

        TokenResponse response = service.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.expiresIn()).isEqualTo(1800L);
        assertThat(userAuth.getLastLoginAt()).isNotNull();
        verify(refreshTokenStore).save(USER_ID, "refresh");
    }

    @Test
    @DisplayName("가입되지 않은 이메일 — AUTH_INVALID_CREDENTIAL, 허가만 얻고 대조 · 쓰기 · 보관은 없다")
    void unknownEmailIsRejectedWithoutMatchingOrWriting() {
        when(userAuthRepository.findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL));

        verify(passwordEncoder).awaitCapacity();
        verify(passwordEncoder, never()).matches(any(), any());
        verifyNoInteractions(refreshTokenStore);
        verify(userAuthRepository, never()).findById(any());
        assertThat(transactionManager.writeBoundaries).isZero();
    }

    @Test
    @DisplayName("비밀번호 불일치 — 가입되지 않은 이메일과 같은 코드, 쓰기 · 보관 없음")
    void wrongPasswordIsRejectedWithoutWritingOrStoring() {
        UserAuth userAuth = stubEmailAuth();
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL));

        assertThat(userAuth.getLastLoginAt()).isNull();
        verify(userAuthRepository, never()).findById(any());
        verifyNoInteractions(refreshTokenStore);
        assertThat(transactionManager.writeBoundaries).isZero();
    }

    @Test
    @DisplayName("비밀번호 대조는 트랜잭션 밖에서 한다 — 느린 연산이 커넥션을 쥐지 않는다")
    void passwordMatchRunsOutsideTransaction() {
        stubEmailAuth();
        List<Boolean> activeDuringMatch = new ArrayList<>();
        when(passwordEncoder.matches(anyString(), anyString())).thenAnswer(invocation -> {
            activeDuringMatch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });

        service.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(activeDuringMatch).containsExactly(false);
        // 경계가 아예 없었다면 위 단언이 의미가 없다 — 읽기 한 번, 쓰기 한 번이 실제로 열렸음을 함께 확인한다.
        assertThat(transactionManager.readBoundaries).isEqualTo(1);
        assertThat(transactionManager.writeBoundaries).isEqualTo(1);
    }

    @Test
    @DisplayName("리프레시 토큰 보관(Redis)은 트랜잭션 밖에서 한다")
    void refreshTokenStoreRunsOutsideTransaction() {
        stubEmailAuth();
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        List<Boolean> activeDuringSave = new ArrayList<>();
        doAnswer(invocation -> {
            activeDuringSave.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(refreshTokenStore).save(any(), anyString());

        service.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(activeDuringSave).containsExactly(false);
        assertThat(transactionManager.writeBoundaries).isEqualTo(1);
    }

    @Test
    @DisplayName("조회와 갱신 사이에 인증 수단이 사라졌으면 토큰을 발급하지 않고 실패와 같게 답한다")
    void authDisappearingBetweenReadAndWriteIsRejected() {
        stubEmailAuth();
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(userAuthRepository.findById(AUTH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL));

        verifyNoInteractions(refreshTokenStore);
    }

    private UserAuth stubEmailAuth() {
        User user = User.signUpWithEmail("홍길동", EMAIL, null);
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        UserAuth userAuth = UserAuth.signUpWithEmail(user, EMAIL, HASH);
        ReflectionTestUtils.setField(userAuth, "authId", AUTH_ID);
        when(userAuthRepository.findByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL)).thenReturn(Optional.of(userAuth));
        when(userAuthRepository.findById(AUTH_ID)).thenReturn(Optional.of(userAuth));
        return userAuth;
    }

    /** 커넥션 없이 경계의 열림만 기록하는 트랜잭션 매니저. 읽기 전용 경계와 쓰기 경계를 나눠 센다. */
    private static final class BoundaryRecordingTransactionManager extends AbstractPlatformTransactionManager {

        int readBoundaries;
        int writeBoundaries;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (definition.isReadOnly()) {
                readBoundaries++;
            } else {
                writeBoundaries++;
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
