package com.duri.rentalplatform.domain.user.repository;

import com.duri.rentalplatform.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사용자 저장소. 중복 가입 확인은 인증 수단({@code auth_type + provider_id})으로 하므로 {@code UserAuthRepository}의 몫이다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 탈퇴하지 않은 사용자. 프로필 수정 대상 조회에 쓴다. */
    Optional<User> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 사용자 행을 쓰기 잠금으로 읽는다({@code SELECT … FOR UPDATE}). 트랜잭션이 끝날 때까지 같은 사용자의 다른 잠금 요청이
     * 기다린다 — 사용자 단위로 「지우고 다시 넣기」를 직렬화할 때 쓴다(알림 구독 설정 수정).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
