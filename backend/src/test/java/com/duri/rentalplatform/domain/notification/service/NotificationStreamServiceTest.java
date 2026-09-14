package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NotificationStreamService} 의 연결 수명 — 토큰 남은 시간, 설정 상한, 하한. */
class NotificationStreamServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final Duration MAX = Duration.ofMinutes(30);

    private final NotificationStreamService service =
            new NotificationStreamService(new SseEmitterStore(), MAX, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("토큰이 1분 남았으면 수명도 1분 — 만료 뒤까지 연결이 유지되지 않는다")
    void timeoutFollowsRemainingTokenLifetime() {
        assertThat(service.timeoutFor(NOW.plusSeconds(60))).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("남은 시간이 설정 상한보다 길면 상한")
    void timeoutCappedByConfiguredMax() {
        assertThat(service.timeoutFor(NOW.plus(Duration.ofHours(2)))).isEqualTo(MAX);
    }

    @Test
    @DisplayName("만료가 코앞이거나 지났으면 하한 1초 — 연결 직후 주석은 나간다")
    void timeoutHasFloor() {
        assertThat(service.timeoutFor(NOW.plusMillis(200))).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.timeoutFor(NOW.minusSeconds(5))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("만료 시각을 모르면 설정 상한")
    void unknownExpiryUsesMax() {
        assertThat(service.timeoutFor(null)).isEqualTo(MAX);
    }
}
