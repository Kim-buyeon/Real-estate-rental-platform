package com.duri.rentalplatform.domain.risk.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link BatchSuccessStore} 를 실제 Redis 에 대고 확인한다 — 키 이름 · 값 형식 · 만료, 그리고 날짜 비교.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BatchSuccessStoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Autowired
    BatchSuccessStore successStore;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clear() {
        for (DailyBatch batch : DailyBatch.values()) {
            stringRedisTemplate.delete(BatchSuccessStore.keyOf(batch));
        }
    }

    @Test
    @DisplayName("기록이 없으면 성공하지 않은 것이다")
    void noRecord() {
        assertThat(successStore.hasSucceeded(DailyBatch.PROPERTY_REFRESH, TODAY)).isFalse();
    }

    @Test
    @DisplayName("성공을 기록하면 키 batch:last-success:{배치} 에 yyyy-MM-dd 가 7일 이하 만료로 들어가고, 그날은 성공으로 읽힌다")
    void markSucceeded() {
        successStore.markSucceeded(DailyBatch.REGISTRY_REFRESH, TODAY);

        String key = "batch:last-success:registry-refresh";
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("2026-09-30");
        assertThat(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS))
                .isPositive()
                .isLessThanOrEqualTo(BatchSuccessStore.TTL.toSeconds());
        assertThat(successStore.hasSucceeded(DailyBatch.REGISTRY_REFRESH, TODAY)).isTrue();
    }

    @Test
    @DisplayName("전날 기록은 오늘 성공으로 보지 않는다")
    void previousDayIsNotToday() {
        successStore.markSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, TODAY.minusDays(1));

        assertThat(successStore.hasSucceeded(DailyBatch.MOCK_LEDGER_REPLACE, TODAY)).isFalse();
    }

    @Test
    @DisplayName("배치마다 기록이 따로다")
    void recordsArePerBatch() {
        successStore.markSucceeded(DailyBatch.PROPERTY_REFRESH, TODAY);

        assertThat(successStore.hasSucceeded(DailyBatch.PROPERTY_REFRESH, TODAY)).isTrue();
        assertThat(successStore.hasSucceeded(DailyBatch.REGISTRY_REFRESH, TODAY)).isFalse();
    }
}
