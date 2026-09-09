package com.duri.rentalplatform.common;

import lombok.Getter;

/**
 * 업무 규칙 위반 예외. 오류 구분은 ErrorCode가 담당하므로 도메인별 예외 클래스를 만들지 않는다.
 * RuntimeException을 직접 던지지 않고 이 예외로만 던진다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String field;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, String field) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.field = field;
    }
}
