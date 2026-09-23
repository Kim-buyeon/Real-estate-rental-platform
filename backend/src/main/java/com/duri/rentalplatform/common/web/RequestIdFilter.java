package com.duri.rentalplatform.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 하나를 식별하는 값을 MDC 에 넣어 그 요청이 남기는 모든 로그에 같은 값이 붙게 한다.
 *
 * <p>앱은 두 슬롯으로 뜨고 앞단 Nginx 가 요청을 나눈다. 식별자가 없으면 한 요청의 로그가 두 컨테이너의 출력에
 * 섞여 어느 줄이 한 묶음인지 알 수 없다.
 *
 * <p><b>생성보다 수신이 기본 경로다.</b> 앞단 Nginx 가 {@code $request_id} 로 {@value #REQUEST_ID_HEADER} 를
 * 만들어 넘기므로, 앞단 접근 로그와 앱 로그가 같은 값으로 이어진다. 헤더가 없을 때만 새로 만든다 — 앞단을
 * 거치지 않는 호출(컨테이너 내부 헬스 체크, 로컬 개발)이 그 경우다.
 *
 * <p><b>받은 값을 그대로 믿지 않는다.</b> 길이({@value #MAX_LENGTH}자)와 허용 문자(영숫자 · 하이픈)를 검사해
 * 어긋나면 버리고 새로 만든다. 검사가 없으면 개행이 섞인 헤더 하나로 로그에 가짜 줄을 심을 수 있고(로그 인젝션),
 * 긴 헤더로 모든 줄을 부풀릴 수 있다. 값을 고쳐 쓰지 않고 통째로 버린다 — 잘라 쓰면 다른 요청과 같은 값이 될 수 있다.
 *
 * <p>응답에도 같은 값을 실어 준다. 오류를 신고한 사용자가 가진 것은 응답뿐이고, 그 값으로 로그를 찾는다.
 *
 * <p>필터 순서는 가장 바깥이다. 다른 필터(시큐리티 포함)가 남기는 로그에도 값이 붙어야 하고, 인증이 필요 없는
 * 경로(정적 자원 · {@code /actuator/health})도 대상이다. 그래서 시큐리티 체인 안이 아니라 서블릿 컨테이너에
 * 등록한다 — {@code Filter} 빈은 Boot 가 컨테이너에 자동 등록하고 {@link Order} 로 순서를 잡는다.
 * {@code SecurityConfig} 는 건드리지 않는다. 시큐리티 체인에 넣으면 그 체인을 타는 경로에만 걸린다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 앞단 Nginx 가 넘기고 응답에도 되돌려 주는 헤더. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 로그 패턴이 읽는 MDC 키 — {@code logback-spring.xml}. */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 받은 값의 길이 상한. UUID(36자) · Nginx {@code $request_id}(32자)를 모두 담고도 남는다. */
    static final int MAX_LENGTH = 64;

    /** 허용 문자. 개행 · 공백 · 제어 문자가 들어갈 자리를 남기지 않는다. */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]+");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀이 스레드를 재사용한다. 지우지 않으면 다음 요청의 로그에 앞 요청의 값이 붙는다.
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    /** 받은 헤더 값을 쓸지 새로 만들지 정한다. 형식에 어긋나면 채택하지 않는다. */
    static String resolveTraceId(String headerValue) {
        if (headerValue == null
                || headerValue.isEmpty()
                || headerValue.length() > MAX_LENGTH
                || !ALLOWED.matcher(headerValue).matches()) {
            return UUID.randomUUID().toString();
        }
        return headerValue;
    }
}
