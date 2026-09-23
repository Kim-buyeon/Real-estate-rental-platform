package com.duri.rentalplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link RequestIdFilter} — 앞단이 넘긴 값을 쓰되 형식을 검사하고, 응답에 되돌려 주며, 요청이 끝나면 MDC 를 비운다.
 *
 * <p>컨테이너를 띄우지 않는다. 필터 하나의 동작이라 목 요청 · 목 응답으로 충분하다.
 */
class RequestIdFilterTest {

    private static final String HEADER = RequestIdFilter.REQUEST_ID_HEADER;
    private static final String MDC_KEY = RequestIdFilter.TRACE_ID_MDC_KEY;

    private final RequestIdFilter filter = new RequestIdFilter();

    /** 실행 중 MDC 에 담겨 있던 값을 꺼내 둔다 — 필터가 끝나면 지워지므로 체인 안에서만 볼 수 있다. */
    private final AtomicReference<String> seenInChain = new AtomicReference<>();

    private final FilterChain chain = (req, res) -> seenInChain.set(MDC.get(MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse invoke(String headerValue)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");
        if (headerValue != null) {
            request.addHeader(HEADER, headerValue);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("헤더로 받은 값을 MDC 에 넣고 응답 헤더로 돌려준다")
    void usesIncomingHeader() throws Exception {
        String incoming = "c1d2e3f405162738495a6b7c8d9e0f11";

        MockHttpServletResponse response = invoke(incoming);

        assertThat(seenInChain.get()).isEqualTo(incoming);
        assertThat(response.getHeader(HEADER)).isEqualTo(incoming);
    }

    @Test
    @DisplayName("헤더가 없으면 새로 만든다 — 허용 형식이고 응답 헤더와 같은 값이다")
    void generatesWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = invoke(null);

        String generated = seenInChain.get();
        assertThat(generated)
                .isNotNull()
                .matches("[A-Za-z0-9-]+")
                .hasSizeLessThanOrEqualTo(RequestIdFilter.MAX_LENGTH);
        assertThat(response.getHeader(HEADER)).isEqualTo(generated);
    }

    @Test
    @DisplayName("길이 상한을 넘는 헤더는 채택하지 않는다")
    void rejectsTooLongHeader() throws Exception {
        String tooLong = "a".repeat(RequestIdFilter.MAX_LENGTH + 1);

        MockHttpServletResponse response = invoke(tooLong);

        assertThat(seenInChain.get()).isNotEqualTo(tooLong).matches("[A-Za-z0-9-]+");
        assertThat(response.getHeader(HEADER)).isNotEqualTo(tooLong);
    }

    @Test
    @DisplayName("개행이 섞인 헤더는 채택하지 않는다 — 로그에 가짜 줄을 심을 수 없다")
    void rejectsHeaderWithNewline() throws Exception {
        String injected = "abc123\nINFO  --- [fake] forged log line";

        MockHttpServletResponse response = invoke(injected);

        assertThat(seenInChain.get()).doesNotContain("\n").matches("[A-Za-z0-9-]+");
        assertThat(response.getHeader(HEADER)).isNotEqualTo(injected);
    }

    @Test
    @DisplayName("허용 문자 밖의 헤더는 채택하지 않는다")
    void rejectsHeaderWithDisallowedCharacters() throws Exception {
        String odd = "trace id/../%00";

        invoke(odd);

        assertThat(seenInChain.get()).isNotEqualTo(odd).matches("[A-Za-z0-9-]+");
    }

    @Test
    @DisplayName("요청이 끝나면 MDC 를 비운다 — 스레드가 재사용되어도 앞 요청의 값이 남지 않는다")
    void clearsMdcAfterRequest() throws Exception {
        invoke("first-request-id");

        assertThat(seenInChain.get()).isEqualTo("first-request-id");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("체인이 예외를 던져도 MDC 를 비운다")
    void clearsMdcWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
