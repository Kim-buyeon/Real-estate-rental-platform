package com.duri.rentalplatform.external;

import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * 외부 연동 폴백의 원인 기록. 폴백이 던지는 {@code EXTERNAL_API_UNAVAILABLE} 에는 원인이 실리지 않아, 남기지 않으면 타임아웃 ·
 * 접속 거부 · 제공처 오류 응답 · 서킷 열림({@code CallNotPermittedException})을 가를 단서가 없다.
 *
 * <p>원인 예외의 종류 · 메시지는 WARN, 스택은 DEBUG 로 남긴다. 스택까지 WARN 으로 두면 서킷이 열린 동안 요청마다 스택이 쌓인다.
 *
 * <p><b>인증키 가림</b> — RestClient 의 I/O 오류 메시지에는 요청 URI 가 그대로 들어가고, 공공데이터포털 · 도로명주소 API 는
 * 인증키를 쿼리 파라미터({@code serviceKey} · {@code confmKey})로 받는다. 로그에 키가 남지 않게 값을 가린다. DEBUG 스택도 가린
 * 메시지로 다시 만든 예외로 남긴다(원인 사슬 포함).
 */
public final class ExternalFallbackLog {

    private static final Pattern KEY_PARAMETER =
            Pattern.compile("((?:serviceKey|confmKey)=)[^&\\s\"']*", Pattern.CASE_INSENSITIVE);
    private static final String MASK = "$1***";

    /** 원인 사슬을 다시 만들 때의 깊이 상한. 순환 사슬에서 끝없이 돌지 않게 막는다. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private ExternalFallbackLog() {
    }

    /**
     * 폴백 원인을 남긴다.
     *
     * @param log   폴백이 있는 클래스의 로거
     * @param api   연동 이름(로그 문구에 들어간다)
     * @param cause 폴백에 넘어온 원인
     */
    public static void warn(Logger log, String api, Throwable cause) {
        log.warn("[{}] 외부 연동 실패 — 폴백 cause={}: {}", api, cause.getClass().getName(), mask(cause.getMessage()));
        if (log.isDebugEnabled()) {
            log.debug("[{}] 외부 연동 실패 스택", api, masked(cause, 0));
        }
    }

    /** 메시지 안의 인증키 값을 가린다. null 이면 null. */
    public static String mask(String message) {
        return message == null ? null : KEY_PARAMETER.matcher(message).replaceAll(MASK);
    }

    private static Throwable masked(Throwable cause, int depth) {
        Throwable copy = new MaskedCause(cause.getClass().getName() + ": " + mask(cause.getMessage()));
        copy.setStackTrace(cause.getStackTrace());
        if (cause.getCause() != null && cause.getCause() != cause && depth < MAX_CAUSE_DEPTH) {
            copy.initCause(masked(cause.getCause(), depth + 1));
        }
        return copy;
    }

    /** 가린 메시지를 실어 나르는 복사본. 원래 예외의 종류는 메시지 앞에 적는다. */
    private static final class MaskedCause extends RuntimeException {

        private MaskedCause(String message) {
            // 원인을 생성자에서 정하지 않는다 — 그러면 initCause 로 사슬을 이을 수 없다.
            super(message);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
