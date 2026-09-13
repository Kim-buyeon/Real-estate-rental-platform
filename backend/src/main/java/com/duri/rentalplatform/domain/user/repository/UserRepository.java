package com.duri.rentalplatform.domain.user.repository;

import com.duri.rentalplatform.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 저장소. 중복 가입 확인은 인증 수단({@code auth_type + provider_id})으로 하므로 {@code UserAuthRepository}의 몫이다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 탈퇴하지 않은 사용자. 프로필 수정 대상 조회에 쓴다. */
    Optional<User> findByUserIdAndDeletedAtIsNull(Long userId);
}
