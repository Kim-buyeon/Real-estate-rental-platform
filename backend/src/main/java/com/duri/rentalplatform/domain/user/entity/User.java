package com.duri.rentalplatform.domain.user.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.user.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 사용자. 가입 정보와 대출 한도 계산에 쓰이는 자격 정보를 함께 갖는다.
 *
 * <p>테이블명을 {@code users}로 지정하는 이유는 기본 테이블명이 되는 {@code user}가 PostgreSQL 예약어이기
 * 때문이다. 비밀번호 해시는 이 엔티티가 갖지 않는다. 인증 수단은 {@code user_auth} 테이블의 몫이다.
 */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // 아래 금액 필드는 데이터베이스 설계서가 BIGINT로 정했고 마이그레이션도 그렇게 만들어져 있다.
    // 원 단위 정수라 Long으로 정확히 표현되며, BigDecimal로 두면 Hibernate가 NUMERIC을 기대해
    // ddl-auto=validate에서 타입이 어긋난다. BigDecimal은 나눗셈과 이율이 들어가는 판정·한도 계산에서 쓴다.
    @Column(nullable = false)
    private Long annualIncome;

    @Column(nullable = false)
    private Integer creditScore;

    @Column(nullable = false)
    private Long existingLoan;

    @Column(nullable = false)
    private Long existingLoanAnnualPayment;

    @Column(nullable = false)
    private boolean hasHouse;

    @Column(nullable = false)
    private Long ownFund;

    /**
     * 이메일 가입으로 사용자를 만든다.
     */
    public static User signUpWithEmail(String name, String email, String phone) {
        User user = new User();
        user.name = name;
        user.email = email;
        user.phone = phone;
        user.role = Role.USER;

        // 가입 요청에는 자격 정보가 없고 컬럼은 NOT NULL이다. 전부 비어 있음을 뜻하는 값으로 채워
        // 이후 자격 정보 입력에서 채우게 한다. 미입력 여부 판별은 이 0과 false를 기준으로 한다.
        user.annualIncome = 0L;
        user.creditScore = 0;
        user.existingLoan = 0L;
        user.existingLoanAnnualPayment = 0L;
        user.hasHouse = false;
        user.ownFund = 0L;

        return user;
    }
}
