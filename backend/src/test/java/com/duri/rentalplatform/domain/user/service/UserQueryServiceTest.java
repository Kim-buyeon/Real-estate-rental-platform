package com.duri.rentalplatform.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.enums.Role;
import com.duri.rentalplatform.domain.user.mapper.UserProfileMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link UserQueryService} — 매퍼 결과 조립, 미입력 항목 판별, 없는 사용자 처리. */
class UserQueryServiceTest {

    private static final long USER_ID = 42L;

    private static final ProfileResponse.Account ACCOUNT = new ProfileResponse.Account("홍길동", "010-1234-5678",
            "user@example.com", Role.USER, OffsetDateTime.of(2026, 7, 1, 9, 12, 0, 0, ZoneOffset.ofHours(9)));

    private UserProfileMapper mapper;
    private UserQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserProfileMapper.class);
        service = new UserQueryService(mapper);
    }

    @Test
    @DisplayName("소득 · 신용점수가 있으면 missingFields 가 비고, 두 묶음을 그대로 담는다")
    void completeProfileHasNoMissingFields() {
        ProfileResponse.Profile profile = profile(42_000_000L, 820);
        when(mapper.selectAccount(USER_ID)).thenReturn(ACCOUNT);
        when(mapper.selectProfile(USER_ID)).thenReturn(profile);

        ProfileResponse response = service.getProfile(USER_ID);

        assertThat(response.account()).isEqualTo(ACCOUNT);
        assertThat(response.profile()).isEqualTo(profile);
        assertThat(response.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("소득 0 · 신용점수 0 이면 둘 다 missingFields 에 명세 필드명으로 든다")
    void zeroIncomeAndScoreAreMissing() {
        when(mapper.selectAccount(USER_ID)).thenReturn(ACCOUNT);
        when(mapper.selectProfile(USER_ID)).thenReturn(profile(0L, 0));

        assertThat(service.getProfile(USER_ID).missingFields()).containsExactly("annualIncome", "creditScore");
    }

    @Test
    @DisplayName("기존 대출 · 연 상환액 · 자기자금 0 과 주택 미보유는 미입력으로 보지 않는다")
    void zeroLoanAndFundAreNotMissing() {
        when(mapper.selectAccount(USER_ID)).thenReturn(ACCOUNT);
        when(mapper.selectProfile(USER_ID))
                .thenReturn(new ProfileResponse.Profile(1L, 1, 0L, 0L, false, 0L));

        assertThat(service.getProfile(USER_ID).missingFields()).isEmpty();
    }

    @Test
    @DisplayName("신용점수만 0 이면 creditScore 만 든다")
    void onlyScoreMissing() {
        when(mapper.selectAccount(USER_ID)).thenReturn(ACCOUNT);
        when(mapper.selectProfile(USER_ID)).thenReturn(profile(42_000_000L, 0));

        assertThat(service.getProfile(USER_ID).missingFields()).containsExactly("creditScore");
    }

    @Test
    @DisplayName("사용자가 없거나 탈퇴했으면 AUTH_INVALID_CREDENTIAL")
    void userNotFound() {
        when(mapper.selectAccount(USER_ID)).thenReturn(null);
        when(mapper.selectProfile(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getProfile(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    private static ProfileResponse.Profile profile(long annualIncome, int creditScore) {
        return new ProfileResponse.Profile(annualIncome, creditScore, 0L, 0L, false, 50_000_000L);
    }
}
