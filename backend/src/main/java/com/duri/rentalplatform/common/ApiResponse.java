package com.duri.rentalplatform.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API의 공통 응답 봉투. API 명세서 §1.2를 따른다.
 * 성공 시 data에 결과를, 실패 시 error에 사유를 담고 null인 쪽은 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String field) {
        return new ApiResponse<>(false, null, ErrorResponse.of(errorCode, field));
    }
}
