package com.duri.rentalplatform.common.logging;

import java.util.regex.Pattern;

/**
 * 로그 한 줄에서 민감한 값을 가린다. 빈이 아닌 순수 함수 클래스다.
 *
 * <p>{@code users} 테이블은 연소득 · 신용점수 · 기존 대출 · 자기 자금을 이름 · 이메일 · 전화번호와 같은 행에
 * 담는다. 이 값들은 의도적으로 기록하지 않아도 객체의 {@code toString()} 이나 예외 메시지에 실려 로그로
 * 새어 나간다 — 엔티티를 그대로 찍는 한 줄, 영속성 계층이 파라미터를 담아 던지는 예외가 그렇다.
 *
 * <p><b>가릴 값의 정본 목록은 아키텍처 설계서(보안 · 암호화) 4.6 「로그에 남기지 않는 값」이 갖는다.</b> 이
 * 클래스의 키는 그 목록을 옮긴 것이다 — 키를 바꾸면 그 목록과 함께 바꾼다. 금전 정보 · 연락처 외에 비밀(평문
 * 비밀번호 · 비밀번호 해시 · 액세스 · 리프레시 토큰 · 재설정 토큰 · SSE 티켓)이 그 목록에 있다.
 *
 * <p><b>{@code name} 은 가리지 않는다.</b> 같은 목록이 가리지 않는 값으로 정한 것이다. 실명이지만 키가 너무
 * 흔하다 — 슬롯 이름 · 파일 이름 · 헤더 이름 같은 무관한 값이 같은 키로 찍히고, 그것까지 가리면 로그가 읽히지
 * 않는다. 이름만으로는 금전 정보와 연락처에 닿지 못한다.
 *
 * <p><b>가리는 일은 로그에서만 한다.</b> 도메인 객체의 {@code toString()} 을 고치지 않는다. 로그 관심사가
 * 도메인에 침투하면 화면 · 응답에 필요한 값까지 도메인이 감추게 된다.
 *
 * <p>세 형태를 덮는다. 키-값({@code annualIncome=50000000}), JSON({@code "annualIncome":50000000}), 그리고
 * PostgreSQL 제약 위반의 Detail({@code Key (auth_type, provider_id)=(EMAIL, a@b.com) already exists.})이다.
 * 첫째는 record · 엔티티의 {@code toString()}, 둘째는 직렬화된 요청 · 응답 본문, 셋째는 pgjdbc 가 서버의
 * 오류 Detail 을 예외 메시지에 그대로 싣는 것이다. 키는 대소문자를 가리지 않고 스네이크 표기도 함께 받는다 —
 * 컬럼명({@code annual_income})이 그대로 실린 메시지가 있다.
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
     *
     * <p>비밀 쪽 키는 일부러 좁다. 두 규칙 모두 키 바로 뒤에 {@code =} 나 닫는 따옴표를 요구하므로 {@code token} 은
     * {@code tokenType=Bearer} · {@code tokenExpiresAt=} 에, {@code ticket} 은 {@code ticketCount=} 에 걸리지 않는다.
     * 키-값 규칙 앞쪽의 단어 경계는 단어 안에서 서지 않으므로 {@code accessToken} 안의 {@code Token} 에서 시작하지
     * 못하고, {@code accessToken} · {@code refreshToken} 은 접두가 붙은 대안이 키 전체로 맞는다. {@code *token*}
     * 처럼 넓히면 토큰 유형 · 만료 같은 진단 값까지 가려진다. 재설정 토큰은 요청 필드 이름이 {@code token} 이라
     * 따로 두지 않는다. {@code provider_?id} 는 이메일 인증에서 이메일과 같은 값이다.
     */
    private static final String KEYS =
            "annual_?income|credit_?score|existing_?loan[a-z_]*|own_?fund|email|phone(?:_?number)?"
                    + "|(?:new_?)?password(?:_?hash)?|(?:access_?|refresh_?)?token|ticket|provider_?id";

    /** 따옴표로 감싼 값, 아니면 구분자 · 공백 전까지. */
    private static final String KEY_VALUE_VALUE = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^,;)\\]}\\s]*)";

    /** JSON 값은 문자열 · 숫자 · 리터럴 셋이다. */
    private static final String JSON_VALUE = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|null)";

    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)\\b(" + KEYS + ")\\s*=\\s*" + KEY_VALUE_VALUE);

    private static final Pattern JSON =
            Pattern.compile("(?i)\"(" + KEYS + ")\"\\s*:\\s*" + JSON_VALUE);

    /**
     * PostgreSQL 제약 위반 Detail 의 값 묶음 앞부분. 서버가 찍는 꼴은 아래와 같다. 괄호 안 값은 각 타입의 출력
     * 함수 결과를 {@code ", "} 로 이은 것이라 괄호를 포함해 무엇이든 들어간다. pgjdbc 는 이 문장을 줄을 바꾼 뒤
     * {@code Detail: } 뒤에 한 줄로 붙인다({@code ServerErrorMessage.toString()}, {@code logServerErrorDetail} 기본 참).
     * <ul>
     *   <li>고유 제약 — {@code Key (cols)=(vals) already exists.}</li>
     *   <li>외래 키 — {@code Key (cols)=(vals) is not present in table "t".} ·
     *       {@code Key (cols)=(vals) is still referenced from table "t".}</li>
     *   <li>배제 제약 — {@code Key (cols)=(vals) conflicts with existing key (cols)=(vals).} ·
     *       {@code ... conflicts with key (cols)=(vals).} — 뒤쪽은 소문자 {@code key} 라 대소문자를 가리지 않는다</li>
     *   <li>NOT NULL · CHECK — {@code Failing row contains (vals).} — 행 전체가 실려 이메일 · 소득이 함께 나간다</li>
     * </ul>
     * 열 목록은 남긴다 — 어느 제약인지 알아야 원인을 찾는다. 식 인덱스면 열 목록도 괄호를 품으므로 {@code )=(}
     * 까지 최소로 잡는다. 권한이 없어 값 없이 찍히는 {@code Key is not present in table "t".} 은 가릴 것이 없다.
     * 파티션 키 형태({@code Partition key of the failing row contains (cols) = (vals).})는 분할 테이블이 없어
     * 덮지 않는다.
     */
    private static final String PG_DETAIL_HEAD = "(\\bkey \\(.*?\\)=\\(|\\bfailing row contains \\()";

    /**
     * 값 묶음을 닫는 괄호와 그 뒤의 꼬리. 값에 괄호가 들어갈 수 있으므로 첫 {@code )} 에서 끊지 않고, 위 형태의
     * 꼬리가 이어지는 {@code )} 를 묶음의 끝으로 본다. Hibernate 는 Detail 뒤에 {@code ] [insert into ...} 를 같은
     * 줄에 붙이므로 마침표 뒤에 {@code ]} 도 받는다.
     */
    private static final String PG_DETAIL_TAIL =
            "\\)(?:\\s+(?:already exists|is not present in table|is still referenced from table|conflicts with)"
                    + "|\\.(?=[\\s\\]]|$))";

    /**
     * 꼬리를 찾으면 그 앞까지, 못 찾으면 그 줄의 마지막 {@code )} 앞까지를 값으로 보고 가린다. 모르는 꼬리에서
     * 값을 남기느니 같은 줄 뒷부분까지 가리는 편을 택한다. {@code .} 은 줄바꿈을 넘지 않으므로 다음 줄은
     * 건드리지 않는다.
     */
    private static final Pattern PG_DETAIL = Pattern.compile(
            "(?i)" + PG_DETAIL_HEAD + "(?:.*?(?=" + PG_DETAIL_TAIL + ")|[^\\r\\n]*(?=\\)))\\)");

    private SensitiveDataMasker() {}

    /**
     * 민감 키의 값을 {@value #MASK} 로 바꾼 문자열을 돌려준다. 키 자체는 남긴다 — 무엇이 가려졌는지 알아야
     * 로그를 읽을 수 있다.
     *
     * <p>PostgreSQL Detail 을 가장 먼저 덮는다. 값 묶음 안에 {@code email=} 같은 글자가 있으면 키-값 규칙이 값
     * 일부만 덮어 묶음의 끝을 찾지 못할 수 있다. 다음으로 JSON 을 덮는다. 뒤의 키-값 규칙은 키 바로 뒤에
     * {@code =} 를 요구하므로 이미 덮인 자리에는 걸리지 않는다 — Detail 의 {@code provider_id)=(***)} 도 키 뒤에
     * {@code )} 가 있어 걸리지 않는다.
     */
    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = PG_DETAIL.matcher(message).replaceAll("$1" + MASK + ")");
        masked = JSON.matcher(masked).replaceAll("\"$1\":\"" + MASK + "\"");
        return KEY_VALUE.matcher(masked).replaceAll("$1=" + MASK);
    }
}
