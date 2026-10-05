package com.duri.rentalplatform.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.BoundedPasswordEncoder;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import com.duri.rentalplatform.domain.user.sender.PasswordResetMailSender;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.domain.user.store.RefreshTokenStore;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link UserCommandService#signUpWithEmail} — 비밀번호 해시가 트랜잭션(= 커넥션 점유) 밖에서 계산되는지와 중복 처리(#411).
 *
 * <p>경계는 {@code UserCommandServiceLoginTest} 와 같은 방식으로 데이터베이스 없이 확인한다 — 커넥션을 열지 않는 트랜잭션 매니저가
 * 경계의 열림 · 롤백을 기록하고, 해시 · 저장 시점에 {@link TransactionSynchronizationManager#isActualTransactionActive()} 를 읽는다.
 * 실제 제약 위반 · 저장은 통합 테스트가 본다.
 */
class UserCommandServiceSignupTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "P@ssw0rd!1";
    private static final String HASH = "new-hash";

    private UserRepository userRepository;
    private UserAuthRepository userAuthRepository;
    private BoundedPasswordEncoder passwordEncoder;
    private BoundaryRecordingTransactionManager transactionManager;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userAuthRepository = mock(UserAuthRepository.class);
        passwordEncoder = mock(BoundedPasswordEncoder.class);
        transactionManager = new BoundaryRecordingTransactionManager();
        service = new UserCommandService(userRepository, userAuthRepository, passwordEncoder,
                mock(JwtTokenProvider.class), mock(RefreshTokenStore.class), mock(PasswordResetTokenStore.class),
                mock(PasswordResetMailSender.class), transactionManager);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("해시는 트랜잭션 밖에서 계산하고, 중복 확인 · 저장은 쓰기 트랜잭션 하나 안에서 한다")
    void hashesOutsideTransactionAndSavesInside() {
        List<Boolean> activeDuringEncode = new ArrayList<>();
        List<Boolean> activeDuringSave = new ArrayList<>();
        when(passwordEncoder.encode(PASSWORD)).thenAnswer(invocation -> {
            activeDuringEncode.add(TransactionSynchronizationManager.isActualTransactionActive());
            return HASH;
        });
        when(userAuthRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            activeDuringSave.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        });

        service.signUpWithEmail(request());

        assertThat(activeDuringEncode).containsExactly(false);
        assertThat(activeDuringSave).containsExactly(true);
        assertThat(transactionManager.writeBoundaries).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();

        ArgumentCaptor<UserAuth> saved = ArgumentCaptor.forClass(UserAuth.class);
        verify(userAuthRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(HASH);
        assertThat(saved.getValue().getProviderId()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("해시가 503 SERVICE_BUSY 면 트랜잭션을 열지 않는다 — 데이터베이스에 닿지 않는다")
    void serviceBusyOpensNoTransaction() {
        when(passwordEncoder.encode(PASSWORD)).thenThrow(new BusinessException(ErrorCode.SERVICE_BUSY));

        assertErrorCode(() -> service.signUpWithEmail(request()), ErrorCode.SERVICE_BUSY);

        assertThat(transactionManager.writeBoundaries).isZero();
        verifyNoInteractions(userRepository, userAuthRepository);
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 USER_DUPLICATED 이고 저장 없이 롤백한다 — 계산한 해시는 버린다")
    void duplicatedEmailRollsBack() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(userAuthRepository.existsByAuthTypeAndProviderId(AuthType.EMAIL, EMAIL)).thenReturn(true);

        assertErrorCode(() -> service.signUpWithEmail(request()), ErrorCode.USER_DUPLICATED);

        verify(userRepository, never()).save(any());
        verify(userAuthRepository, never()).saveAndFlush(any());
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    @Test
    @DisplayName("존재 확인 뒤 동시 가입이 UNIQUE 제약에 걸리면 409 USER_DUPLICATED 로 바꾸고 롤백한다")
    void uniqueViolationBecomesDuplicated() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(userAuthRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

        assertErrorCode(() -> service.signUpWithEmail(request()), ErrorCode.USER_DUPLICATED);

        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    private static SignupRequest request() {
        return new SignupRequest(EMAIL, PASSWORD, "홍길동", null);
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(expected));
    }

    /** 커넥션 없이 쓰기 경계의 열림과 롤백만 기록하는 트랜잭션 매니저. */
    private static final class BoundaryRecordingTransactionManager extends AbstractPlatformTransactionManager {

        int writeBoundaries;
        int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (!definition.isReadOnly()) {
                writeBoundaries++;
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
