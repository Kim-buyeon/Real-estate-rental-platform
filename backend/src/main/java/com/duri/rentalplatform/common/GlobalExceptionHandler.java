package com.duri.rentalplatform.common;

import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

/**
 * 전역 예외 처리. 모든 오류를 공통 응답 봉투(§1.2)로 변환한다.
 * 상태 코드는 ErrorCode가 갖고 여기서 적용한다. 컨트롤러에 try-catch를 두지 않는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 업무 규칙 위반. 상태와 코드는 ErrorCode를 따른다. 다음 요청 가능 시각이 있으면 함께 담는다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode, e.getField(), e.getRetryAfter()));
    }

    /** 요청 본문 검증 실패. 첫 위반 필드를 함께 담아 400으로 변환한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String field = fieldError != null ? fieldError.getField() : null;
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, field));
    }

    /** 경로 변수 · 요청 파라미터의 타입 불일치(예: 숫자 자리에 문자). 400으로 변환한다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getName()));
    }

    /**
     * 본문을 읽을 수 없음 — 깨진 JSON, 열거에 없는 값(예: {@code contractType}), 숫자 자리에 문자. 명세 1.3 「형식 오류」.
     * 처리하지 않으면 500 이 된다. 역직렬화가 위치를 알면 속성 이름을 점으로 이어 {@code field} 에 담는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, unreadableField(e)));
    }

    private static String unreadableField(HttpMessageNotReadableException e) {
        if (!(e.getCause() instanceof JacksonException jackson)) {
            return null;
        }
        String field = jackson.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return field.isEmpty() ? null : field;
    }

    /** 필수 요청 파라미터 누락(명세 1.3 「필수 파라미터 누락」). 처리하지 않으면 500 이 된다. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getParameterName()));
    }

    /**
     * 컨트롤러 · 서비스 안에서 난 권한 거부(메서드 보안 등). 필터 단계의 거부는 접근 거부 처리기가 맡지만, 디스패처
     * 안에서 던져진 것은 여기로 온다. 처리하지 않으면 아래 {@code Exception} 처리기가 500 으로 바꾼다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.AUTH_FORBIDDEN.getStatus())
                .body(ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN, null));
    }

    /** 예상하지 못한 예외. 내부 메시지를 응답에 노출하지 않고 500으로 변환한다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, null));
    }
}
