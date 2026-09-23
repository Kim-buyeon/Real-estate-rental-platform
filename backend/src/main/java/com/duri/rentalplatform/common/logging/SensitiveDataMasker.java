package com.duri.rentalplatform.common.logging;

import java.util.regex.Pattern;

/**
 * 로그 한 줄에서 민감한 값을 가린다. 빈이 아닌 순수 함수 클래스다.
 *
 * <p>{@code users} 테이블은 연소득 · 신용점수 · 기존 대출 · 자기 자금을 이름 · 이메일 · 전화번호와 같은 행에
 * 담는다. 이 값들은 의도적으로 기록하지 않아도 객체의 {@code toString()} 이나 예외 메시지에 실려 로그로
 * 새어 나간다 — 엔티티를 그대로 찍는 한 줄, 영속성 계층이 파라미터를 담아 던지는 예외가 그렇다.
 *
 * <p><b>{@code name} 은 가리지 않는다.</b> 실명이지만 키가 너무 흔하다 — 슬롯 이름 · 파일 이름 · 헤더 이름
 * 같은 무관한 값이 같은 키로 찍히고, 그것까지 가리면 로그가 읽히지 않는다. 금전 정보와 연락처를 가리는 것이
 * 이 클래스의 목적이고, 이름만으로는 그 둘에 닿지 못한다. 가려야 할 컬럼의 정본 목록은 아직 없다 —
 * 정해지면 이 판단을 다시 본다.
 *
 * <p><b>가리는 일은 로그에서만 한다.</b> 도메인 객체의 {@code toString()} 을 고치지 않는다. 로그 관심사가
 * 도메인에 침투하면 화면 · 응답에 필요한 값까지 도메인이 감추게 된다.
 *
 * <p>두 형태를 덮는다. 키-값({@code annualIncome=50000000})과 JSON({@code "annualIncome":50000000})이다.
 * 전자는 record · 엔티티의 {@code toString()} 이고 후자는 직렬화된 요청 · 응답 본문이다. 키는 대소문자를
 * 가리지 않고 스네이크 표기도 함께 받는다 — 컬럼명({@code annual_income})이 그대로 실린 메시지가 있다.
 *
 * <p>덜 가리는 것보다 더 가리는 편을 택한다. {@code existingLoanId} 처럼 민감하지 않은 키가 접두 때문에
 * 함께 가려질 수 있으나, 가려진 식별자는 다른 로그로 찾을 수 있고 새어 나간 신용점수는 되돌릴 수 없다.
 */
public final class SensitiveDataMasker {

    /** 가린 자리에 남기는 문자열. */
    static final String MASK = "***";

    /**
     * 가릴 키. {@code existing_?loan[a-z_]*} 는 {@code existingLoan} 과
     * {@code existingLoanAnnualPayment} 을 함께 받는다(대소문자를 가리지 않으므로 {@code AnnualPayment} 에도 걸린다).
     */
    private static final String KEYS =
            "annual_?income|credit_?score|existing_?loan[a-z_]*|own_?fund|email|phone(?:_?number)?";

    /** 따옴표로 감싼 값, 아니면 구분자 · 공백 전까지. */
    private static final String KEY_VALUE_VALUE = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^,;)\\]}\\s]*)";

    /** JSON 값은 문자열 · 숫자 · 리터럴 셋이다. */
    private static final String JSON_VALUE = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|null)";

    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)\\b(" + KEYS + ")\\s*=\\s*" + KEY_VALUE_VALUE);

    private static final Pattern JSON =
            Pattern.compile("(?i)\"(" + KEYS + ")\"\\s*:\\s*" + JSON_VALUE);

    private SensitiveDataMasker() {}

    /**
     * 민감 키의 값을 {@value #MASK} 로 바꾼 문자열을 돌려준다. 키 자체는 남긴다 — 무엇이 가려졌는지 알아야
     * 로그를 읽을 수 있다.
     *
     * <p>JSON 을 먼저 덮는다. 뒤의 키-값 규칙은 {@code =} 를 요구하므로 이미 덮인 자리에는 걸리지 않는다.
     */
    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = JSON.matcher(message).replaceAll("\"$1\":\"" + MASK + "\"");
        return KEY_VALUE.matcher(masked).replaceAll("$1=" + MASK);
    }
}
