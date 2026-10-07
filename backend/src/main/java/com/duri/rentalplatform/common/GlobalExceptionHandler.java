package com.duri.rentalplatform.common;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
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

    /**
     * 위 처리기가 받지 않은 모든 예외. DB 가 바쁜 경우({@link #databaseBusyCause})는 503 {@code SERVICE_BUSY}, 나머지는 예상하지
     * 못한 예외로 보고 내부 메시지를 노출하지 않은 채 500 으로 변환한다.
     *
     * <p>DB 가 바쁜 경우를 별도 처리기로 가르지 않고 여기서 보는 이유 — 같은 원인이 경로마다 다른 Spring 예외로 감싸여 올라온다.
     * 최상위 타입으로 처리기를 고르면 빠지는 경로가 생긴다. 원인 체인 맨 아래의 JDBC 예외는 경로와 무관하게 같으므로 그것으로
     * 판정하고, 그러려면 모든 예외가 오는 이 자리여야 한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        SQLException busy = databaseBusyCause(e);
        if (busy != null) {
            // 풀 고갈 · 질의 취소는 운영 신호라 warn. 부하에서 한꺼번에 쏟아지므로 스택은 남기지 않는다 — Hikari 메시지에 풀 상태
            // (total · active · idle · waiting)가, PostgreSQL 메시지에 취소 사유가 들어 있다.
            log.warn("Database busy ({}, SQLState {}): {}", e.getClass().getName(), busy.getSQLState(), busy.getMessage());
            return ResponseEntity.status(ErrorCode.SERVICE_BUSY.getStatus())
                    .body(ApiResponse.fail(ErrorCode.SERVICE_BUSY, null));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, null));
    }

    /** PostgreSQL 의 질의 취소 SQLSTATE — statement_timeout(30s, 설정 파일) 초과 또는 운영자의 취소. */
    static final String QUERY_CANCELED = "57014";

    /**
     * 읽기 노드(standby)에서 복제 반영과 충돌해 취소된 질의의 SQLSTATE. PostgreSQL 은 복제 충돌 취소를
     * {@code ERRCODE_T_R_SERIALIZATION_FAILURE}(40001)로 보낸다. 앱은 READ COMMITTED 만 쓰므로 쓰기 노드(primary)에서는 나지 않는다.
     */
    static final String REPLICATION_CONFLICT_CANCELED = "40001";

    /** 원인 체인을 몇 단계까지 따라갈지. 정상 체인은 서너 단계다 — 순환하는 체인에서 멈추기 위한 상한이다. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * 원인 체인에서 「DB 가 바쁘다」를 뜻하는 JDBC 예외를 찾는다. 없으면 null.
     *
     * <ul>
     *   <li>{@link SQLTransientConnectionException} — 풀에서 커넥션을 얻지 못했다. HikariCP 는 대여 대기
     *       ({@code connection-timeout})가 넘으면 이 타입을 던진다(HikariCP 7.0.2 {@code HikariPool.createTimeoutException}).
     *       DB 에 접속하지 못해 풀이 채워지지 않은 경우도 같은 타입이다 — 그때 원인은 드라이버의 접속 실패다. pgJDBC 는 이 타입을
     *       던지지 않는다.</li>
     *   <li>SQLSTATE {@value #QUERY_CANCELED} — 질의가 취소됐다. pgJDBC 의 {@code PSQLException} 이다.</li>
     *   <li>SQLSTATE {@value #REPLICATION_CONFLICT_CANCELED} — 읽기 노드에서 복제 반영과 충돌해 질의가 취소됐다. 다시 하면 되는
     *       취소라 버그(500)와 섞지 않는다. pgJDBC 의 {@code PSQLException} 이다.</li>
     * </ul>
     *
     * <p>감싸는 모양은 경로마다 다르다(Spring 7.0 · Hibernate 7.4 · mybatis-spring 4.1 소스, 단위 테스트가 고정한다).
     * <ul>
     *   <li>JPA 트랜잭션 시작에서 커넥션을 잡을 때 — {@code CannotCreateTransactionException} ← Hibernate
     *       {@code JDBCConnectionException} ← 풀 예외({@code JpaTransactionManager.doBegin}).</li>
     *   <li>읽기 분산을 켜 커넥션을 첫 문장까지 미룰 때 · MyBatis — Hibernate 변환을 거친 Spring 예외 또는
     *       {@code TransientDataAccessResourceException}({@code SQLExceptionSubclassTranslator}).</li>
     *   <li>트랜잭션 밖에서 커넥션을 얻을 때 — {@code CannotGetJdbcConnectionException}({@code DataSourceUtils}).</li>
     *   <li>질의 취소 — Spring {@code QueryTimeoutException}. Hibernate 는 PostgreSQL 방언이 57014 를
     *       {@code org.hibernate.QueryTimeoutException} 으로 바꾼 뒤 Spring 이 다시 감싼다.</li>
     * </ul>
     * 어느 경우든 원래의 JDBC 예외가 원인으로 남는다.
     */
    static SQLException databaseBusyCause(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException connection) {
                return connection;
            }
            if (t instanceof SQLException sql && (QUERY_CANCELED.equals(sql.getSQLState())
                    || REPLICATION_CONFLICT_CANCELED.equals(sql.getSQLState()))) {
                return sql;
            }
        }
        return null;
    }
}
