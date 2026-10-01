package com.duri.rentalplatform.domain.risk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.MockLedgerReplaceJobLauncher;
import com.duri.rentalplatform.domain.risk.vo.MockLedgerReplaceReport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link MockLedgerReplaceScheduler} — 서울 날짜로 배치를 부르고, 날짜 락을 못 잡으면 실행 없이 끝낸다. 켜짐 조건(enabled ·
 * 대장 연동 real)과 기동 뒤 실행이 없음을 애노테이션으로 확인한다.
 */
class MockLedgerReplaceSchedulerTest {

    /** UTC 9/30 19:30 = 서울 10/1 04:30. 날짜가 서버 시간대가 아니라 서울로 정해지는지 가른다. */
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-09-30T19:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEOUL_DATE = LocalDate.of(2026, 10, 1);

    private MockLedgerReplaceJobLauncher jobLauncher;
    private MockLedgerReplaceScheduler scheduler;

    @BeforeEach
    void setUp() {
        jobLauncher = mock(MockLedgerReplaceJobLauncher.class);
        scheduler = new MockLedgerReplaceScheduler(jobLauncher, UTC_CLOCK);
    }

    @Test
    @DisplayName("서울 기준 날짜로 배치를 부른다")
    void runsWithSeoulDate() {
        when(jobLauncher.run(SEOUL_DATE)).thenReturn(new MockLedgerReplaceReport());

        boolean ran = scheduler.replaceMockLedgers();

        verify(jobLauncher).run(SEOUL_DATE);
        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("날짜 락을 다른 인스턴스가 잡고 있으면(503) 예외 없이 끝내고 false 를 돌려준다")
    void dateLockContentionEndsQuietly() {
        when(jobLauncher.run(SEOUL_DATE)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatCode(() -> scheduler.replaceMockLedgers()).doesNotThrowAnyException();
        assertThat(scheduler.replaceMockLedgers()).isFalse();
    }

    @Test
    @DisplayName("락 경합이 아닌 예외는 삼키지 않는다")
    void otherFailurePropagates() {
        when(jobLauncher.run(SEOUL_DATE)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> scheduler.replaceMockLedgers()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("스케줄은 설정 키의 cron 을 서울 시간대로 쓰고, 기동 뒤 실행 경로는 없다")
    void scheduledUsesConfiguredCronInSeoulWithoutStartupRun() throws NoSuchMethodException {
        Scheduled scheduled = MockLedgerReplaceScheduler.class.getMethod("replaceMockLedgers")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${risk.batch.mock-ledger-replace.cron}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        assertThat(MockLedgerReplaceScheduler.class.getMethods())
                .noneMatch(method -> method.isAnnotationPresent(EventListener.class));
    }

    @Test
    @DisplayName("enabled 가 참이고 대장 연동이 real 일 때만 뜬다")
    void conditionalOnEnabledAndRealMode() {
        ConditionalOnBooleanProperty enabled =
                MockLedgerReplaceScheduler.class.getAnnotation(ConditionalOnBooleanProperty.class);
        ConditionalOnProperty mode = MockLedgerReplaceScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(enabled.value()).containsExactly("risk.batch.mock-ledger-replace.enabled");
        assertThat(mode.prefix()).isEqualTo("external.building-ledger");
        assertThat(mode.name()).containsExactly("mode");
        assertThat(mode.havingValue()).isEqualTo("real");
    }
}
