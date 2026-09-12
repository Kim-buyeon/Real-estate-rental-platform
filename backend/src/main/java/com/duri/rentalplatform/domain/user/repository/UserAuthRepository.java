package com.duri.rentalplatform.domain.user.repository;

import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 인증 수단 저장소. 중복 가입 확인은 {@code users.email}이 아니라 인증 수단의
 * {@code auth_type + provider_id} 짝으로 하므로 {@code UserRepository}가 아닌 이곳에 둔다.
 */
public interface UserAuthRepository extends JpaRepository<UserAuth, Long> {

    // 이 검사만으로는 중복 가입을 막지 못한다. 조회와 저장 사이에 같은 짝의 다른 요청이 끼어들면 둘 다 통과하며,
    // 실제 방어선은 스키마의 UNIQUE(auth_type, provider_id)다. 이 메서드는 흔한 경우에 제약 위반 대신
    // 명세의 오류 코드를 사용자에게 돌려주기 위한 것이다.
    boolean existsByAuthTypeAndProviderId(AuthType authType, String providerId);

    Optional<UserAuth> findByAuthTypeAndProviderId(AuthType authType, String providerId);
}
