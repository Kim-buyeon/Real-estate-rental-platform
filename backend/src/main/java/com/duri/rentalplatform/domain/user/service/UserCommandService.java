package com.duri.rentalplatform.domain.user.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.LoginRequest;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetConfirmRequest;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetRequest;
import com.duri.rentalplatform.domain.user.dto.request.ProfileUpdateRequest;
import com.duri.rentalplatform.domain.user.dto.request.ReissueRequest;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.dto.response.TokenResponse;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import com.duri.rentalplatform.domain.user.sender.PasswordResetMailSender;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.domain.user.store.RefreshTokenStore;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자와 인증 수단을 변경하는 서비스. 이메일 회원 가입, 로그인·재발급·로그아웃, 자격 정보 수정, 비밀번호 재설정을 담당한다.
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
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final PasswordResetMailSender passwordResetMailSender;

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

    /**
     * 계정 · 자격 정보를 수정한다. 조회한 엔티티의 변경 메서드를 부르고 변경 감지가 UPDATE 한다.
     *
     * <p>사용자가 없거나 탈퇴했으면 401 {@code AUTH_INVALID_CREDENTIAL} 이다. 유효한 토큰이 가리키는 사용자가 없는 것은
     * 정상 경로가 아니고, 공통 오류표에 사용자 없음 코드가 없다. {@link #reissue}의 판단과 같다.
     */
    @Transactional
    public void updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL));

        ProfileUpdateRequest.Account account = request.account();
        ProfileUpdateRequest.Profile profile = request.profile();
        user.changeAccount(account.name(), account.phone());
        user.changeQualification(profile.annualIncome(), profile.creditScore(), profile.existingLoan(),
                profile.existingLoanAnnualPayment(), profile.hasHouse(), profile.ownFund());
    }

    /**
     * 비밀번호 재설정 메일을 요청한다 — API 명세(회원) 1.3. <b>결과를 돌려주지 않는다.</b> 가입되지 않은 이메일 · 소셜 전용 계정 ·
     * 발송 간격 안의 재요청도 예외 없이 조용히 끝나, 응답으로 가입 여부를 알 수 없다.
     *
     * <p><b>요청 스레드는 계정 확인(DB 조회 1회)까지만 한다.</b> 간격 · 토큰 발급 · 발송은 계정이 있을 때만 일어나는 일이라, 여기서
     * 하면 계정이 있는 요청만 Redis 왕복만큼 느려지고 Redis 장애 때 그 요청만 500 이 되어 가입 여부가 드러난다. 그래서 회원 식별자와
     * 이메일만 비동기 쪽({@link PasswordResetMailSender})에 넘기고 끝낸다. 두 경로의 차이는 비동기 제출 하나다.
     *
     * <p>트랜잭션을 열지 않는다. 데이터베이스 접근이 인증 수단 한 건 조회뿐이고 그 조회는 저장소가 자기 트랜잭션에서 한다. 커밋을
     * 기다릴 쓰기가 없다({@link #reissue} 와 같은 판단).
     */
    public void requestPasswordReset(PasswordResetRequest request) {
        // 이메일 인증 수단만 찾는다. 소셜 계정의 provider_id 는 제공자 식별자라 여기 걸리지 않고, 소셜 전용 회원은
        // 비밀번호가 없어 대상이 아니다 — 기능 정의(회원) USER-06.
        UserAuth userAuth = userAuthRepository
                .findByAuthTypeAndProviderId(AuthType.EMAIL, request.email())
                .orElse(null);
        if (userAuth == null) {
            return;
        }

        // 연관 프록시의 식별자는 초기화 없이 읽힌다 — 트랜잭션 밖이어도 회원 조회가 나가지 않는다.
        passwordResetMailSender.send(userAuth.getUser().getUserId(), request.email());
    }

    /**
     * 재설정 토큰으로 새 비밀번호를 설정한다 — API 명세(회원) 1.3.
     *
     * <p>비밀번호 규칙은 컨트롤러의 입력 검증이 이미 봤다. 규칙을 어긴 요청은 여기 닿지 않으므로 토큰이 소비되지 않는다 — 입력
     * 실수 한 번에 메일부터 다시 받게 하지 않는다.
     *
     * <p>토큰은 {@code GETDEL} 로 먼저 소비한다. 두 요청이 같은 토큰으로 들어와도 하나만 비밀번호를 바꾼다. 소비 뒤 데이터베이스
     * 저장이 실패하면 토큰은 사라지고 비밀번호는 그대로다 — 다시 요청하면 되고, 반대(바뀌었는데 토큰이 남음)보다 안전하다.
     *
     * <p>리프레시 토큰 폐기가 커밋보다 앞선다. 커밋이 실패하면 비밀번호는 그대로인데 로그인만 끊긴 상태가 되지만, 반대로 커밋 뒤에
     * 폐기가 실패하면 옛 비밀번호로 얻은 세션이 살아남는다. 끊기는 쪽을 고른다. 액세스 토큰은 만료까지 남는다(명세 1.3, 로그아웃과 같다).
     */
    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        Long userId = passwordResetTokenStore.consume(request.token())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));

        // 토큰을 발급한 뒤 인증 수단이 사라진 경우다(탈퇴 등). 토큰은 이미 소비됐고 쓸 곳이 없으니 무효와 같게 답한다.
        UserAuth userAuth = userAuthRepository.findByUserUserIdAndAuthType(userId, AuthType.EMAIL)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));

        userAuth.changePassword(passwordEncoder.encode(request.newPassword()));
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
