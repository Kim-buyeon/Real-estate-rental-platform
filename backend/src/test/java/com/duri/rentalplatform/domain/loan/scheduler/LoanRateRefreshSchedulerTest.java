package com.duri.rentalplatform.domain.loan.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.service.LoanProductRefreshService;
import com.duri.rentalplatform.domain.loan.vo.LoanProductRefreshReport;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/** {@link LoanRateRefreshScheduler} — 서울 기준 전달을 기준월로 부르고, 기동 뒤에는 기준월 행이 없을 때만 부른다. */
class LoanRateRefreshSchedulerTest {

    /** UTC 8/31 16:00 = 서울 9/1 01:00. 기준월이 서버 시간대(7월)가 아니라 서울(8월)로 정해지는지 가른다. */
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-08-31T16:00:00Z"), ZoneOffset.UTC);
    private static final YearMonth BASE_MONTH = YearMonth.of(2026, 8);

    private LoanProductRefreshService refreshService;
    private LoanRateRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        refreshService = mock(LoanProductRefreshService.class);
        scheduler = new LoanRateRefreshScheduler(refreshService, UTC_CLOCK, Runnable::run);
    }

    @Test
    @DisplayName("월 갱신은 서울 기준 전달을 기준월로 부른다")
    void monthlyUsesPreviousSeoulMonth() {
        when(refreshService.refresh(BASE_MONTH)).thenReturn(new LoanProductRefreshReport());

        scheduler.refreshMonthly();

        verify(refreshService).refresh(BASE_MONTH);
    }

    @Test
    @DisplayName("기준월 락을 다른 인스턴스가 잡고 있으면(503) 예외 없이 끝낸다")
    void lockContentionEndsQuietly() {
        when(refreshService.refresh(BASE_MONTH)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatCode(() -> scheduler.refreshMonthly()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("월 갱신에서 락 경합이 아닌 예외는 삼키지 않는다")
    void monthlyOtherFailurePropagates() {
        when(refreshService.refresh(BASE_MONTH)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> scheduler.refreshMonthly()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기동 뒤: 기준월 행이 없으면 갱신한다")
    void startupRefreshesWhenStale() {
        when(refreshService.isStale(BASE_MONTH)).thenReturn(true);

        scheduler.refreshOnStartupIfStale();

        verify(refreshService).refresh(BASE_MONTH);
    }

    @Test
    @DisplayName("기동 뒤: 기준월 행이 이미 있으면 외부를 부르지 않는다")
    void startupSkipsWhenFresh() {
        when(refreshService.isStale(BASE_MONTH)).thenReturn(false);

        scheduler.refreshOnStartupIfStale();

        verify(refreshService, never()).refresh(any());
    }

    @Test
    @DisplayName("기동 뒤 갱신의 예외는 별도 스레드 밖으로 새지 않는다")
    void startupFailureIsLogged() {
        when(refreshService.isStale(BASE_MONTH)).thenReturn(true);
        when(refreshService.refresh(BASE_MONTH)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> scheduler.refreshOnStartupIfStale()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정 키의 cron 을 서울로 쓰고, enabled 와 real 연동일 때만 뜬다")
    void annotations() throws NoSuchMethodException {
        Scheduled scheduled = LoanRateRefreshScheduler.class.getMethod("refreshMonthly").getAnnotation(Scheduled.class);
        ConditionalOnBooleanProperty enabled =
                LoanRateRefreshScheduler.class.getAnnotation(ConditionalOnBooleanProperty.class);
        ConditionalOnProperty real = LoanRateRefreshScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("${loan.batch.rate-refresh.cron}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        assertThat(enabled.value()).containsExactly("loan.batch.rate-refresh.enabled");
        assertThat(real.prefix()).isEqualTo("external.jeonse-loan-rate");
        assertThat(real.havingValue()).isEqualTo("real");
    }
}
