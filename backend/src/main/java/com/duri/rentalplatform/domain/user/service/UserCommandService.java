package com.duri.rentalplatform.domain.user.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.LoginRequest;
import com.duri.rentalplatform.domain.user.dto.request.ReissueRequest;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.dto.response.TokenResponse;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자와 인증 수단을 변경하는 서비스. 이번 범위에서는 이메일 회원 가입과 로그인·재발급·로그아웃을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    @Transactional
    public void signUpWithEmail(SignupRequest request) {
        if (userAuthRepository.existsByAuthTypeAndProviderId(AuthType.EMAIL, request.email())) {
            throw new BusinessException(ErrorCode.USER_DUPLICATED, "email");
        }

        User user = userRepository.save(
                User.signUpWithEmail(request.name(), request.email(), request.phone()));
        String hashedPassword = passwordEncoder.encode(request.password());
        UserAuth userAuth = UserAuth.signUpWithEmail(user, request.email(), hashedPassword);

        try {
            // save가 아니라 saveAndFlush를 쓰는 이유: save는 INSERT를 커밋 시점까지 미루고 커밋은 이 메서드가
            // 끝난 뒤 트랜잭션 경계에서 일어나므로, 아래 catch가 제약 위반을 잡지 못한다. 플러시를 강제해야
            // 예외가 이 자리에서 난다.
            userAuthRepository.saveAndFlush(userAuth);
        } catch (DataIntegrityViolationException e) {
            // 위의 존재 검사와 이 저장 사이에 같은 이메일의 다른 요청이 끼어들면 둘 다 검사를 통과하고,
            // 실제로 막는 것은 UNIQUE(auth_type, provider_id) 제약이다. 그대로 두면 전역 처리기가 500으로
            // 바꾸지만 중복 가입은 409여야 하므로 존재 검사와 같은 예외로 바꿔 던진다.
            log.warn("Signup rejected by a data integrity constraint", e);
            throw new BusinessException(ErrorCode.USER_DUPLICATED, "email");
        }
    }

    /**
     * 이메일과 비밀번호를 대조하고 토큰을 발급한다.
     *
     * <p>쓰기 트랜잭션을 여는 이유는 최종 로그인 시각 갱신 때문이다. 변경 감지가 UPDATE를 내보내려면
     * 엔티티가 이 경계 안에서 영속 상태로 남아 있어야 한다.
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserAuth userAuth = userAuthRepository
                .findByAuthTypeAndProviderId(AuthType.EMAIL, request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL));

        // 가입되지 않은 이메일과 비밀번호 불일치에 같은 코드를 주는 이유: 둘을 구분해 응답하면 응답만 보고
        // 어떤 이메일이 가입돼 있는지 하나씩 확인할 수 있다. 가입 여부는 그 자체로 알려 줄 정보가 아니다.
        if (!passwordEncoder.matches(request.password(), userAuth.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }

        // 조회한 엔티티의 변경 메서드를 부르는 것으로 끝낸다. 변경 감지가 UPDATE를 수행하므로 save를 부르지 않는다.
        userAuth.updateLastLoginAt(LocalDateTime.now());

        return issueTokens(userAuth.getUser());
    }

    /**
     * 리프레시 토큰을 검증하고 토큰 두 벌을 새로 발급한다.
     *
     * <p>트랜잭션을 열지 않는 이유: 데이터베이스 접근이 사용자 한 건 조회뿐이고, 그 조회는 저장소가 이미
     * 자기 트랜잭션 안에서 수행한다. 여기서 경계를 열면 그 앞뒤의 Redis 왕복 두 번 동안 데이터베이스
     * 커넥션을 붙잡고 있게 된다. 바뀌는 것은 Redis에 보관된 토큰뿐이라 되돌릴 대상도 없다.
     */
    public TokenResponse reissue(ReissueRequest request) {
        Long userId = jwtTokenProvider.parseRefreshTokenUserId(request.refreshToken());

        // 서명이 유효해도 쓸 수 있는 토큰이라는 뜻은 아니다. 이미 회전돼 밀려난 이전 토큰이거나 로그아웃으로
        // 폐기된 토큰도 만료 전까지는 서명 검증을 통과한다. 보관된 현재 토큰인지는 여기서만 걸러낼 수 있다.
        if (!refreshTokenStore.isCurrentToken(userId, request.refreshToken())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }

        // 사용자가 없을 때 404가 아니라 401을 주는 이유: 서명이 유효한 토큰이 가리키는 사용자가 없는 것은
        // 정상 경로가 아니다. 없음을 404로 알려 주면 식별자를 바꿔 가며 어떤 사용자가 존재하는지 훑을 수 있다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL));

        return issueTokens(user);
    }

    /**
     * 보관된 리프레시 토큰을 폐기한다.
     *
     * <p>액세스 토큰은 무효화하지 않는다. 명세가 정한 로그아웃은 리프레시 토큰 폐기이고, 남은 액세스 토큰까지
     * 막으려면 폐기 목록을 두고 요청마다 Redis를 조회해야 해서 인증 자체가 상태를 갖게 된다. 남은 액세스
     * 토큰은 짧은 만료로 사라지고, 재발급 경로는 위에서 이미 끊긴다.
     *
     * <p>트랜잭션을 열지 않는 이유: 데이터베이스를 건드리지 않는다. 경계를 열면 쓰지도 않을 커넥션을 빌린다.
     *
     * <p>이미 없는 키를 지우는 것은 오류가 아니므로 두 번 로그아웃해도 실패하지 않는다.
     */
    public void logout(Long userId) {
        refreshTokenStore.delete(userId);
    }

    /** 토큰 두 벌을 발급하고 리프레시 토큰을 보관한다. 같은 키에 덮어쓰는 것이 곧 회전이다. */
    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());
        refreshTokenStore.save(user.getUserId(), refreshToken);

        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessTokenValiditySeconds());
    }
}
