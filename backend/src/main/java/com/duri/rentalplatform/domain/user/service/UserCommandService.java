package com.duri.rentalplatform.domain.user.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.entity.User;
import com.duri.rentalplatform.domain.user.entity.UserAuth;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import com.duri.rentalplatform.domain.user.repository.UserAuthRepository;
import com.duri.rentalplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자와 인증 수단을 변경하는 서비스. 이번 범위에서는 이메일 회원 가입을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final PasswordEncoder passwordEncoder;

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
}
