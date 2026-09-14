package com.duri.rentalplatform.domain.risk.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link ReanalyzeIntervalStore} 를 실제 Redis 에 대고 확인한다 — 간격 키의 만료 설정과 남은 시간 조회.
 *
 * <p>간격 값은 설정({@code risk.reanalyze.min-interval})에서 읽어 비교한다. 테스트에 숫자를 박으면 설정이 바뀔 때 테스트가
 * 설정 대신 옛 값을 지킨다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReanalyzeIntervalStoreTest {

    private static final long PROPERTY_ID = 900_002L;

    @Autowired
    ReanalyzeIntervalStore intervalStore;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Value("${risk.reanalyze.min-interval}")
    Duration minInterval;

    @BeforeEach
    void clear() {
        stringRedisTemplate.delete(ReanalyzeIntervalStore.keyOf(PROPERTY_ID));
    }

    @Test
    @DisplayName("분석 기록이 없으면 남은 시간이 없다")
    void noKeyNoRemaining() {
        assertThat(intervalStore.remaining(PROPERTY_ID)).isEmpty();
    }

    @Test
    @DisplayName("분석을 기록하면 키 risk:reanalyze:interval:{propertyId} 가 생기고 남은 시간은 설정 간격 이하다")
    void markAnalyzedSetsInterval() {
        intervalStore.markAnalyzed(PROPERTY_ID);

        assertThat(stringRedisTemplate.hasKey("risk:reanalyze:interval:" + PROPERTY_ID)).isTrue();
        assertThat(intervalStore.remaining(PROPERTY_ID)).hasValueSatisfying(remaining -> assertThat(remaining)
                .isPositive()
                .isLessThanOrEqualTo(minInterval));
    }

    @Test
    @DisplayName("만료가 없는 키는 간격으로 보지 않는다")
    void keyWithoutTtlIsNotInterval() {
        stringRedisTemplate.opsForValue().set(ReanalyzeIntervalStore.keyOf(PROPERTY_ID), "1");

        assertThat(intervalStore.remaining(PROPERTY_ID)).isEmpty();
    }
}
