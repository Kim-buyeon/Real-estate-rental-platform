package com.duri.rentalplatform.domain.risk.store;

import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 하루 한 번 도는 배치의 마지막 성공 날짜(서울). 배치 시작기가 회차를 COMPLETED 로 끝냈을 때 쓰고, 기동 뒤 따라잡기가 「오늘 이미
 * 돌았는가」를 읽는다. 예약 실행과 기동 실행이 같은 키를 쓴다.
 *
 * <p>키 {@code batch:last-success:{배치}}, 값은 {@code yyyy-MM-dd}. 네 슬롯이 같은 기록을 봐야 하므로 Redis 에 둔다. 만료는 7일 —
 * 오늘인지만 보므로 하루면 충분하지만, 며칠 꺼 두었다 켰을 때 마지막 성공일을 눈으로 확인할 수 있게 넉넉히 둔다. 날짜 락과 달리
 * 실행 여부를 가르지 않는다 — 동시에 도는 것은 날짜 락이 막고, 이 기록은 이미 끝난 회차를 다시 시작하지 않게 할 뿐이다.
 */
@Component
public class BatchSuccessStore {

    static final String KEY_PREFIX = "batch:last-success:";
    static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;

    public BatchSuccessStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 그 날짜의 회차가 성공했다. 이미 있으면 덮는다. */
    public void markSucceeded(DailyBatch batch, LocalDate date) {
        stringRedisTemplate.opsForValue().set(keyOf(batch), date.toString(), TTL);
    }

    /** 그 날짜의 회차가 이미 성공했는가. 기록이 없거나 다른 날짜면 거짓이다. */
    public boolean hasSucceeded(DailyBatch batch, LocalDate date) {
        return date.toString().equals(stringRedisTemplate.opsForValue().get(keyOf(batch)));
    }

    static String keyOf(DailyBatch batch) {
        return KEY_PREFIX + batch.getKey();
    }
}
