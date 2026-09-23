package com.duri.rentalplatform.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 마스킹 변환기 — 민감한 값이 로그 한 줄에 남지 않는다.
 *
 * <p>{@code users} 의 연소득 · 신용점수 · 기존 대출 · 자기 자금과 이메일 · 전화번호를 대상으로, 두 형태를 모두 본다.
 * 키-값(record · 엔티티의 {@code toString()})과 JSON(직렬화된 본문)이다. 메시지 본문뿐 아니라 예외 메시지도 확인한다 —
 * 새는 자리는 대개 던져진 예외다.
 */
class MaskingConverterTest {

    private static final LoggerContext CONTEXT = new LoggerContext();
    private static final Logger LOGGER = CONTEXT.getLogger("masking-test");

    private final MaskingMessageConverter messageConverter = new MaskingMessageConverter();

    private String convertMessage(String message) {
        return messageConverter.convert(
                new LoggingEvent("fqcn", LOGGER, Level.INFO, message, null, null));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "User[userId=1, annualIncome=50000000]        | annualIncome=***    | 50000000",
                "User[userId=1, creditScore=780]              | creditScore=***     | 780",
                "User[existingLoan=120000000]                 | existingLoan=***    | 120000000",
                "User[existingLoanAnnualPayment=4800000]      | existingLoanAnnualPayment=*** | 4800000",
                "User[ownFund=30000000]                       | ownFund=***         | 30000000",
                "User[email=tenant@example.com]               | email=***           | tenant@example.com",
                "User[phone=010-1234-5678]                    | phone=***           | 010-1234-5678",
                "row: annual_income=50000000                  | annual_income=***   | 50000000"
            })
    @DisplayName("키-값 형태의 민감 값을 가린다")
    void masksKeyValue(String message, String expectedFragment, String secret) {
        String converted = convertMessage(message);

        assertThat(converted).contains(expectedFragment).doesNotContain(secret);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"annualIncome\":50000000}       | 50000000",
                "{\"creditScore\": 780}            | 780",
                "{\"existingLoan\":120000000}      | 120000000",
                "{\"ownFund\" : 30000000}          | 30000000",
                "{\"email\":\"tenant@example.com\"}| tenant@example.com",
                "{\"phone\":\"010-1234-5678\"}     | 010-1234-5678"
            })
    @DisplayName("JSON 형태의 민감 값을 가린다")
    void masksJson(String message, String secret) {
        String converted = convertMessage(message);

        assertThat(converted).contains("\"***\"").doesNotContain(secret);
    }

    @Test
    @DisplayName("한 줄에 여러 개가 있어도 모두 가린다 — 민감하지 않은 필드는 그대로 둔다")
    void masksEveryOccurrenceAndKeepsOtherFields() {
        String converted =
                convertMessage(
                        "User[userId=7, name=김두리, email=tenant@example.com,"
                                + " phone=010-1234-5678, creditScore=780, hasHouse=false]");

        assertThat(converted)
                .contains("userId=7", "name=김두리", "hasHouse=false")
                .contains("email=***", "phone=***", "creditScore=***")
                .doesNotContain("tenant@example.com", "010-1234-5678", "780");
    }

    @Test
    @DisplayName("예외 메시지의 민감 값도 가린다")
    void masksThrowableMessage() {
        MaskingThrowableProxyConverter converter = new MaskingThrowableProxyConverter();
        converter.setContext(CONTEXT);
        converter.start();
        IllegalStateException exception =
                new IllegalStateException(
                        "insert failed: email=tenant@example.com, annualIncome=50000000");

        String converted = converter.throwableProxyToString(new ThrowableProxy(exception));

        assertThat(converted)
                .contains("email=***", "annualIncome=***")
                .doesNotContain("tenant@example.com", "50000000");
    }

    @Test
    @DisplayName("민감 키가 없는 메시지는 바꾸지 않는다")
    void leavesOtherMessagesUntouched() {
        String message = "property 12 analyzed: grade=SAFE, deposit=250000000";

        assertThat(convertMessage(message)).isEqualTo(message);
    }
}
