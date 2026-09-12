package com.duri.rentalplatform.domain.user.repository;

import com.duri.rentalplatform.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 저장소. 가입에 필요한 것은 {@code save} 하나뿐이라 조회 메서드를 두지 않았으며,
 * 중복 가입 확인은 인증 수단({@code auth_type + provider_id})으로 하므로 {@code UserAuthRepository}의 몫이다.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
