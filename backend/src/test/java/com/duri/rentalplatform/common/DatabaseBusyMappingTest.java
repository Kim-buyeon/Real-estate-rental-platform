package com.duri.rentalplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.hibernate.QueryTimeoutException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * DB 가 바쁜 두 경우(풀 대여 시간 초과 · 질의 취소)가 어느 경로로 감싸여 와도 503 {@code SERVICE_BUSY} 가 되는지(#402).
 *
 * <p>감싼 예외는 손으로 고르지 않고 가능한 한 <b>실제 변환기</b>로 만든다 — Hibernate 쪽은 {@link HibernateJpaDialect}, MyBatis 쪽은
 * {@link MyBatisExceptionTranslator} + PostgreSQL 오류 코드 표({@code SQLErrorCodeSQLExceptionTranslator}, mybatis-spring 의 기본과
 * 같은 종류). 변환기가 돌려주는 타입도 함께 고정한다 — 라이브러리가 감싸는 타입을 바꾸면 여기서 드러난다. 변환기를 거치지 않는
 * 두 경로(JPA 트랜잭션 시작 · 트랜잭션 밖 커넥션)는 그 소스가 만드는 모양 그대로 만든다.
 *
 * <p>풀 시간 초과는 HikariCP 가 던지는 타입({@link SQLTransientConnectionException})과 메시지 모양을 따른다. 질의 취소는 pgJDBC 의
 * {@code PSQLException} 대신 같은 SQLSTATE 의 {@link SQLException} 을 쓴다 — 드라이버는 테스트 컴파일 경로에 없고(runtimeOnly),
 * 판정은 타입이 아니라 SQLSTATE 로 한다.
 */
class DatabaseBusyMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static SQLTransientConnectionException poolTimeout() {
        return new SQLTransientConnectionException(
                "primary - Connection is not available, request timed out after 5001ms "
                        + "(total=5, active=5, idle=0, waiting=3)");
    }

    private static SQLException queryCanceled() {
        return new SQLException("ERROR: canceling statement due to statement timeout", "57014");
    }

    private static DataAccessException myBatisTranslate(SQLException cause) {
        MyBatisExceptionTranslator translator =
                new MyBatisExceptionTranslator(() -> new SQLErrorCodeSQLExceptionTranslator("PostgreSQL"), false);
        // MyBatis 는 JDBC 예외를 자기 PersistenceException 으로 감싸 올리고, SqlSessionTemplate 이 이 변환기로 Spring 예외로 바꾼다.
        return translator.translateExceptionIfPossible(new PersistenceException("### Error querying database.", cause));
    }

    private static DataAccessException hibernateTranslate(RuntimeException hibernate) {
        return new HibernateJpaDialect().translateExceptionIfPossible(hibernate);
    }

    private void assertBusy(Exception e) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(e);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("SERVICE_BUSY");
        assertThat(response.getBody().error().message()).doesNotContain("Connection is not available", "canceling");
    }

    private void assertInternal(Exception e) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(e);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("풀 시간 초과 - JPA 트랜잭션 시작(JpaTransactionManager.doBegin)이 감싼 CannotCreateTransactionException 은 503")
    void poolTimeoutAtJpaTransactionBegin() {
        // Hibernate 가 커넥션을 잡다 실패하면 JDBCConnectionException 으로 바꾸고(SQLExceptionTypeDelegate), 관리자가 다시 감싼다.
        Exception e = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new JDBCConnectionException("Unable to acquire JDBC Connection", poolTimeout()));
        assertBusy(e);
    }

    @Test
    @DisplayName("풀 시간 초과 - 커넥션을 첫 문장까지 미룬 JPA 조회(Hibernate 변환)도 503")
    void poolTimeoutThroughHibernateTranslation() {
        DataAccessException e = hibernateTranslate(
                new JDBCConnectionException("Unable to acquire JDBC Connection", poolTimeout()));
        assertThat(e).isNotNull().hasRootCauseInstanceOf(SQLTransientConnectionException.class);
        assertBusy(e);
    }

    @Test
    @DisplayName("풀 시간 초과 - MyBatis 변환은 TransientDataAccessResourceException 이고 503")
    void poolTimeoutThroughMyBatis() {
        DataAccessException e = myBatisTranslate(poolTimeout());
        assertThat(e).isInstanceOf(TransientDataAccessResourceException.class);
        assertBusy(e);
    }

    @Test
    @DisplayName("풀 시간 초과 - 트랜잭션 밖 커넥션(DataSourceUtils)의 CannotGetJdbcConnectionException 은 503")
    void poolTimeoutOutsideTransaction() {
        assertBusy(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", poolTimeout()));
    }

    @Test
    @DisplayName("질의 취소(57014) - Hibernate(PostgreSQL 방언의 QueryTimeoutException)를 Spring 이 감싼 QueryTimeoutException 은 503")
    void queryCanceledThroughHibernate() {
        DataAccessException e = hibernateTranslate(
                new QueryTimeoutException("could not execute statement", queryCanceled(), "select 1"));
        assertThat(e).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertBusy(e);
    }

    @Test
    @DisplayName("질의 취소(57014) - MyBatis 변환은 QueryTimeoutException 이고 503")
    void queryCanceledThroughMyBatis() {
        DataAccessException e = myBatisTranslate(queryCanceled());
        assertThat(e).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertBusy(e);
    }

    @Test
    @DisplayName("직렬화 실패(40001, 읽기 노드 복제 충돌 취소) - Hibernate 변환 경로도 503")
    void serializationFailureThroughHibernate() {
        DataAccessException e = hibernateTranslate(new org.hibernate.exception.LockAcquisitionException(
                "could not execute statement", serializationFailure(), "select 1"));
        assertThat(e).isNotNull();
        assertBusy(e);
    }

    @Test
    @DisplayName("직렬화 실패(40001) - MyBatis 변환 경로도 503")
    void serializationFailureThroughMyBatis() {
        DataAccessException e = myBatisTranslate(serializationFailure());
        assertThat(e).isNotNull();
        assertBusy(e);
    }

    @Test
    @DisplayName("연결 실패(08006)는 503 이 아니라 500")
    void connectionFailureIsInternal() {
        DataAccessException e = myBatisTranslate(new SQLException("connection failure", "08006"));
        assertThat(e).isNotNull();
        assertInternal(e);
    }

    private static SQLException serializationFailure() {
        return new SQLException("ERROR: canceling statement due to conflict with recovery", "40001");
    }

    @Test
    @DisplayName("다른 SQLSTATE 의 DB 오류(중복 키 23505)는 503 이 아니라 500")
    void otherSqlStateIsInternal() {
        DataAccessException e = myBatisTranslate(new SQLException("duplicate key value", "23505"));
        assertThat(e).isInstanceOf(DataIntegrityViolationException.class);
        assertInternal(e);
    }

    @Test
    @DisplayName("DB 와 무관한 예외는 500")
    void unrelatedIsInternal() {
        assertInternal(new IllegalStateException("x"));
    }

    @Test
    @DisplayName("원인 체인이 순환해도 멈추고 500")
    void cyclicCauseTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertInternal(a);
    }
}
