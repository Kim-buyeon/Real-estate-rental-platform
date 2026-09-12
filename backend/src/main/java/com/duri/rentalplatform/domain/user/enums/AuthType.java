package com.duri.rentalplatform.domain.user.enums;

/**
 * 인증 수단. 데이터베이스 설계서 USER_AUTH 표의 auth_type 컬럼(VARCHAR(10))에 @Enumerated(EnumType.STRING)으로
 * 매핑되며 provider_id와 함께 유니크 제약을 이루므로, 저장 값이 곧 상수명이다. 상수명을 바꾸면 기존 행의 값과 어긋난다.
 * KAKAO와 NAVER는 소셜 가입이 차기 범위라 아직 사용처가 없다. 이 열거형이 컬럼의 값 집합 자체를 나타내기 위해
 * 함께 둔 것이므로, 사용처가 없다는 이유로 지우지 않는다.
 */
public enum AuthType {
    EMAIL,
    KAKAO,
    NAVER
}
