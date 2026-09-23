package com.duri.rentalplatform.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.status.Status;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@code logback-spring.xml} 이 실제로 읽히는지, 그 패턴이 무엇을 내보내는지 확인한다.
 *
 * <p>설정 파일의 오류는 컴파일로 드러나지 않는다. 변환기 클래스 이름 오타 하나면 기동 로그에 오류만 남고 패턴이
 * 조용히 기본값으로 돌아간다 — 그러면 추적 값도 마스킹도 사라진다. 그래서 파일을 그대로 읽어 콘솔 인코더가 만든
 * 한 줄을 본다.
 *
 * <p>애플리케이션 컨텍스트를 띄우지 않는다. 별도 {@link LoggerContext} 에 설정을 올리므로 테스트 실행 자체의
 * 로그 설정에는 영향이 없다.
 */
class LogbackConsolePatternTest {

    private LoggerContext context;

    @BeforeEach
    void configure() throws Exception {
        URL configuration = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(configuration).as("logback-spring.xml 이 클래스패스에 있다").isNotNull();

        context = new LoggerContext();
        // 직접 만든 컨텍스트에는 MDC 어댑터가 없다. 패턴이 %X 로 MDC 를 읽으므로 실제 어댑터를 넣어 준다.
        context.setMDCAdapter(MDC.getMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(configuration);
    }

    @AfterEach
    void stop() {
        MDC.clear();
        context.stop();
    }

    private String render(String message) {
        Logger logger = context.getLogger("pattern-test");
        ConsoleAppender<ILoggingEvent> console = console();
        Encoder<ILoggingEvent> encoder = console.getEncoder();
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, message, null, null);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private ConsoleAppender<ILoggingEvent> console() {
        return (ConsoleAppender<ILoggingEvent>)
                context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
    }

    @Test
    @DisplayName("설정이 오류 없이 읽힌다 — 변환기 등록과 패턴 치환이 성립한다")
    void configurationHasNoErrors() {
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .filteredOn(status -> status.getLevel() == Status.ERROR)
                .isEmpty();
        assertThat(console()).isNotNull();
    }

    @Test
    @DisplayName("MDC 의 traceId 가 로그 한 줄에 실린다")
    void includesTraceId() {
        MDC.put("traceId", "abc-123");

        assertThat(render("checked")).contains("[abc-123]");
    }

    @Test
    @DisplayName("요청 밖의 로그에는 기본값이 실린다 — 빈 대괄호를 남기지 않는다")
    void fallsBackWhenTraceIdMissing() {
        assertThat(render("startup")).contains("[no-trace]");
    }

    @Test
    @DisplayName("패턴을 거친 출력에서 민감 값이 가려진다")
    void masksSensitiveValues() {
        String line = render("User[email=tenant@example.com, creditScore=780]");

        assertThat(line)
                .contains("email=***", "creditScore=***")
                .doesNotContain("tenant@example.com", "780");
    }

    @Test
    @DisplayName("파일 출력을 두지 않는다 — stdout 만 쓰고 회전은 로그 드라이버가 맡는다")
    void hasNoFileAppender() {
        context.getLogger(Logger.ROOT_LOGGER_NAME)
                .iteratorForAppenders()
                .forEachRemaining(appender ->
                        assertThat(appender).isNotInstanceOf(FileAppender.class));
    }
}
