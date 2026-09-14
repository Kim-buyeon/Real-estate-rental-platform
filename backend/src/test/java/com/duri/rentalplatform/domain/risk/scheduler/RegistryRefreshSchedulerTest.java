package com.duri.rentalplatform.domain.risk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.RegistryRefreshJobLauncher;
import com.duri.rentalplatform.domain.risk.vo.RegistryRefreshReport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link RegistryRefreshScheduler} — 서울 날짜로 배치를 부르고, 날짜 락을 못 잡으면 실행 없이 끝낸다. 락 자체는 배치 시작기의
 * 애노테이션과 관점이 갖는다.
 */
class RegistryRefreshSchedulerTest {

    /** UTC 9/13 18:30 = 서울 9/14 03:30. 날짜가 서버 시간대가 아니라 서울로 정해지는지 가른다. */
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEOUL_DATE = LocalDate.of(2026, 9, 14);

    private RegistryRefreshJobLauncher jobLauncher;
    private RegistryRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        jobLauncher = mock(RegistryRefreshJobLauncher.class);
        scheduler = new RegistryRefreshScheduler(jobLauncher, UTC_CLOCK);
    }

    @Test
    @DisplayName("서울 기준 날짜로 배치를 부른다")
    void runsWithSeoulDate() {
        when(jobLauncher.run(SEOUL_DATE)).thenReturn(new RegistryRefreshReport());

        scheduler.refreshWishlistedRegistries();

        verify(jobLauncher).run(SEOUL_DATE);
    }

    @Test
    @DisplayName("날짜 락을 다른 인스턴스가 잡고 있으면(503) 예외 없이 끝낸다")
    void dateLockContentionEndsQuietly() {
        when(jobLauncher.run(SEOUL_DATE)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        assertThatCode(() -> scheduler.refreshWishlistedRegistries()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("락 경합이 아닌 예외는 삼키지 않는다")
    void otherFailurePropagates() {
        when(jobLauncher.run(SEOUL_DATE)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> scheduler.refreshWishlistedRegistries()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("스케줄은 설정 키의 cron 을 서울 시간대로 쓴다")
    void scheduledUsesConfiguredCronInSeoul() throws NoSuchMethodException {
        Scheduled scheduled = RegistryRefreshScheduler.class.getMethod("refreshWishlistedRegistries")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${risk.batch.registry-refresh.cron}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
