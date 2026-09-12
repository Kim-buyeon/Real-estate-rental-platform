package com.duri.rentalplatform.domain.user.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.user.enums.AuthType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 인증 수단. 이메일 가입의 비밀번호 해시와 소셜 가입의 제공자 식별자를 이 엔티티가 갖고
 * {@link User}는 갖지 않는다.
 */
@Entity
@Getter
@Table(name = "user_auth")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuth extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long authId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuthType authType;

    // 컬럼 설명은 OAuth 제공자가 발급한 식별자지만, 이메일 가입에서는 이메일 주소를 넣는다.
    // 스키마의 UNIQUE(auth_type, provider_id)가 중복 가입을 막는 유일한 제약인데,
    // 이메일 가입에서 이 값을 NULL로 두면 PostgreSQL이 NULL끼리는 중복으로 보지 않아
    // 같은 이메일로 몇 번이든 가입된다. users.email에는 유니크 제약이 없다.
    // 이메일을 채워 두면 기존 제약이 그대로 중복 가입을 막으므로 스키마를 바꾸지 않는다.
    @Column(length = 100)
    private String providerId;

    @Column(length = 255)
    private String passwordHash;

    /**
     * 이메일 가입의 인증 수단을 만든다. 해싱은 이 클래스가 하지 않고 이미 해싱된 값을 받는다.
     */
    public static UserAuth signUpWithEmail(User user, String email, String hashedPassword) {
        UserAuth userAuth = new UserAuth();
        userAuth.user = user;
        userAuth.authType = AuthType.EMAIL;
        userAuth.providerId = email;
        userAuth.passwordHash = hashedPassword;

        return userAuth;
    }
}
