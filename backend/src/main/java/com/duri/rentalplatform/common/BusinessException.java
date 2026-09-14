package com.duri.rentalplatform.common;

import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * 업무 규칙 위반 예외. 오류 구분은 ErrorCode가 담당하므로 도메인별 예외 클래스를 만들지 않는다.
 * RuntimeException을 직접 던지지 않고 이 예외로만 던진다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String field;
    /** 다음 요청 가능 시각. 요청 간격 제한(429)에서만 채운다. */
    private final OffsetDateTime retryAfter;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, (String) null);
    }

    public BusinessException(ErrorCode errorCode, String field) {
        this(errorCode, field, null);
    }

    /** 요청 간격 제한 — 응답 error.retryAfter 에 다음 요청 가능 시각을 담는다. */
    public BusinessException(ErrorCode errorCode, OffsetDateTime retryAfter) {
        this(errorCode, null, retryAfter);
    }

    private BusinessException(ErrorCode errorCode, String field, OffsetDateTime retryAfter) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.field = field;
        this.retryAfter = retryAfter;
    }
}
