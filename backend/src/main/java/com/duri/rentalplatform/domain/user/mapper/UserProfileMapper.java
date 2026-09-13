package com.duri.rentalplatform.domain.user.mapper;

import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;

/** 프로필 조회. XML 은 {@code resources/mapper/user/UserProfileMapper.xml}. 탈퇴 사용자는 조회되지 않는다. */
public interface UserProfileMapper {

    /** 계정 정보. 없거나 탈퇴했으면 null. */
    ProfileResponse.Account selectAccount(Long userId);

    /** 자격 정보. 없거나 탈퇴했으면 null. */
    ProfileResponse.Profile selectProfile(Long userId);
}
