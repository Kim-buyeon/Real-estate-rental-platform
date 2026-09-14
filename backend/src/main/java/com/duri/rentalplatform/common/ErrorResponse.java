package com.duri.rentalplatform.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 실패 응답의 error 본문. API 명세서 §1.2의 형식을 따른다.
 * field는 검증 대상 필드가 있을 때만, retryAfter는 다음 요청 가능 시각이 정해진 429에서만 채우며, 없으면 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String field, OffsetDateTime retryAfter) {

    public static ErrorResponse of(ErrorCode errorCode, String field) {
        return of(errorCode, field, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String field, OffsetDateTime retryAfter) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), field, retryAfter);
    }
}
