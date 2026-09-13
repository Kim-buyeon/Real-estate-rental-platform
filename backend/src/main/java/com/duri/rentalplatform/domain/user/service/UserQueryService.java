package com.duri.rentalplatform.domain.user.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.mapper.UserProfileMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 조회. API 명세서(회원 · 인증) 1.1 프로필 조회. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    static final String ANNUAL_INCOME = "annualIncome";
    static final String CREDIT_SCORE = "creditScore";

    private final UserProfileMapper userProfileMapper;

    /**
     * 계정 · 자격 정보와 미입력 항목을 조회한다.
     *
     * <p>사용자가 없거나 탈퇴했으면 404 가 아니라 401 {@code AUTH_INVALID_CREDENTIAL} 이다. 유효한 토큰이 가리키는
     * 사용자가 없는 것은 정상 경로가 아니고, 공통 오류표에 사용자 없음 코드가 없다. 재발급의 판단과 같다.
     */
    public ProfileResponse getProfile(Long userId) {
        ProfileResponse.Account account = userProfileMapper.selectAccount(userId);
        ProfileResponse.Profile profile = userProfileMapper.selectProfile(userId);
        // 두 조회 사이에 탈퇴가 끼면 한쪽만 null 일 수 있어 둘 다 본다.
        if (account == null || profile == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }
        return new ProfileResponse(account, profile, missingFields(profile));
    }

    /**
     * 미입력으로 보는 항목. 소득과 신용점수의 0 만 해당한다.
     *
     * <p>기존 대출 · 연 상환액 · 자기자금 · 주택 보유는 0 · false 가 실제로 있을 수 있는 값이라 미입력과 구분할 수 없어
     * 넣지 않는다. 소득 0 이면 DSR 한도가 0 이 되고, 신용점수 0 은 존재하지 않는 점수다.
     */
    private static List<String> missingFields(ProfileResponse.Profile profile) {
        List<String> missing = new ArrayList<>();
        if (profile.annualIncome() == null || profile.annualIncome() == 0L) {
            missing.add(ANNUAL_INCOME);
        }
        if (profile.creditScore() == null || profile.creditScore() == 0) {
            missing.add(CREDIT_SCORE);
        }
        return List.copyOf(missing);
    }
}
