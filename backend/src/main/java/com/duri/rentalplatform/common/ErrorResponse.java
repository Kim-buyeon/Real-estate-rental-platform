package com.duri.rentalplatform.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 실패 응답의 error 본문. API 명세서 §1.2의 형식을 따른다.
 * field는 검증 대상 필드가 있을 때만 채우며, 없으면 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String field) {

    public static ErrorResponse of(ErrorCode errorCode, String field) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), field);
    }
}
