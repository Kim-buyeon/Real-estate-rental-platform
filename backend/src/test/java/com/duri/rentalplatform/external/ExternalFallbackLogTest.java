package com.duri.rentalplatform.external;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

/** {@link ExternalFallbackLog} — 원인 종류 · 메시지는 WARN, 스택은 DEBUG, 인증키는 둘 다 가린다. */
class ExternalFallbackLogTest {

    private static final String URI =
            "https://apis.data.go.kr/x/getRTMSDataSvcAptRent?serviceKey=abc%2Bdef%3D%3D&LAWD_CD=11110&DEAL_YMD=202609";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("external-fallback-log-test");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    @Test
    @DisplayName("WARN 에 연동 이름 · 원인 종류 · 가린 메시지를 남기고 스택은 싣지 않는다")
    void warnsWithTypeAndMaskedMessage() {
        logger.setLevel(Level.INFO);

        ExternalFallbackLog.warn(logger, "전월세 실거래가", ioError());

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("[전월세 실거래가]")
                    .contains(ResourceAccessException.class.getName())
                    .contains("serviceKey=***&LAWD_CD=11110")
                    .contains("Read timed out")
                    .doesNotContain("abc%2Bdef");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("DEBUG 가 켜져 있으면 스택을 남기되 원인 사슬까지 메시지의 인증키를 가린다")
    void debugStackIsMasked() {
        logger.setLevel(Level.DEBUG);

        ExternalFallbackLog.warn(logger, "전월세 실거래가", new IllegalStateException("감쌈 " + URI, ioError()));

        ILoggingEvent debug = appender.list.stream().filter(event -> event.getLevel() == Level.DEBUG)
                .findFirst().orElseThrow();
        IThrowableProxy top = debug.getThrowableProxy();
        assertThat(top.getMessage()).contains(IllegalStateException.class.getName()).contains("serviceKey=***")
                .doesNotContain("abc%2Bdef");
        assertThat(top.getStackTraceElementProxyArray()).isNotEmpty();
        assertThat(top.getCause().getMessage()).contains("serviceKey=***").doesNotContain("abc%2Bdef");
        assertThat(top.getCause().getCause().getMessage()).contains(SocketTimeoutException.class.getName());
    }

    @Test
    @DisplayName("도로명주소 API 의 confmKey 도 가리고, 메시지가 없으면 null 그대로다")
    void masksConfmKeyAndKeepsNull() {
        assertThat(ExternalFallbackLog.mask("GET https://business.juso.go.kr/addrlink/addrLinkApi.do?confmKey=U01T&keyword=a"))
                .isEqualTo("GET https://business.juso.go.kr/addrlink/addrLinkApi.do?confmKey=***&keyword=a");
        assertThat(ExternalFallbackLog.mask(null)).isNull();
    }

    private static ResourceAccessException ioError() {
        return new ResourceAccessException("I/O error on GET request for \"" + URI + "\": Read timed out",
                new SocketTimeoutException("Read timed out"));
    }
}
