package com.duri.rentalplatform.domain.user.enums;

/**
 * 사용자 권한. 데이터베이스 설계서 USER 표의 role 컬럼(VARCHAR)에 @Enumerated(EnumType.STRING)으로
 * 매핑되므로 저장 값이 곧 상수명이다. 상수명을 바꾸면 기존 행의 값과 어긋난다.
 */
public enum Role {
    USER,
    ADMIN
}
