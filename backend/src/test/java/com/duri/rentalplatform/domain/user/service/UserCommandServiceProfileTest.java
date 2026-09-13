package com.duri.rentalplatform.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.ProfileUpdateRequest;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import com.duri.rentalplatform.domain.user.store.RefreshTokenStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@link UserCommandService#updateProfile} — 수정 가능 필드 반영, null 의 미입력 치환, 없는 사용자 처리. */
class UserCommandServiceProfileTest {

    private static final long USER_ID = 42L;

    private UserRepository userRepository;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserCommandService(userRepository, mock(UserAuthRepository.class),
                mock(PasswordEncoder.class), mock(JwtTokenProvider.class), mock(RefreshTokenStore.class));
    }

    @Test
    @DisplayName("계정 · 자격 정보를 요청 값으로 바꾸고 이메일 · 권한은 그대로 둔다")
    void updatesEditableFields() {
        User user = User.signUpWithEmail("옛이름", "user@example.com", null);
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

        service.updateProfile(USER_ID, new ProfileUpdateRequest(
                new ProfileUpdateRequest.Account("홍길동", "010-1234-5678"),
                new ProfileUpdateRequest.Profile(45_000_000L, 820, 10_000_000L, 1_200_000L, true, 50_000_000L)));

        assertThat(user)
                .extracting(User::getName, User::getPhone, User::getEmail, User::getAnnualIncome,
                        User::getCreditScore, User::getExistingLoan, User::getExistingLoanAnnualPayment,
                        User::isHasHouse, User::getOwnFund)
                .containsExactly("홍길동", "010-1234-5678", "user@example.com", 45_000_000L, 820, 10_000_000L,
                        1_200_000L, true, 50_000_000L);
        assertThat(user.getRole().name()).isEqualTo("USER");
    }

    @Test
    @DisplayName("자격 정보 null 은 미입력 값 0 · false 로 저장된다")
    void nullQualificationBecomesZero() {
        User user = User.signUpWithEmail("홍길동", "user@example.com", null);
        user.changeQualification(1L, 1, 1L, 1L, true, 1L);
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

        service.updateProfile(USER_ID, new ProfileUpdateRequest(
                new ProfileUpdateRequest.Account("홍길동", null),
                new ProfileUpdateRequest.Profile(null, null, null, null, null, null)));

        assertThat(user)
                .extracting(User::getAnnualIncome, User::getCreditScore, User::getExistingLoan,
                        User::getExistingLoanAnnualPayment, User::isHasHouse, User::getOwnFund)
                .containsExactly(0L, 0, 0L, 0L, false, 0L);
    }

    @Test
    @DisplayName("사용자가 없거나 탈퇴했으면 AUTH_INVALID_CREDENTIAL")
    void userNotFound() {
        when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        ProfileUpdateRequest request = new ProfileUpdateRequest(
                new ProfileUpdateRequest.Account("홍길동", null),
                new ProfileUpdateRequest.Profile(null, null, null, null, null, null));

        assertThatThrownBy(() -> service.updateProfile(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL);
    }
}
