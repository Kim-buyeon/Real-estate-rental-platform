package com.duri.rentalplatform.external.buildingledger;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 건축HUB 건축물대장정보 호출의 일일 상한. 설정 {@code external.building-ledger.daily-limit}.
 *
 * <p><b>무엇을 세는가</b> — 제공처에 나가는 요청 한 건(표제부 한 페이지)마다 하나다. {@link RealBuildingLedgerClient} 가
 * 요청을 보내기 직전에 {@link #tryAcquire()} 로 한 칸을 받고, 못 받으면 그 조회를 부르지 않고 빈 값으로 끝낸다. 재시도도
 * 요청을 다시 보내므로 다시 센다. 사용자 조회(위험도 · 대장 조회)와 배치가 같은 카운터를 쓴다.
 *
 * <p><b>상한은 장애가 아니다</b> — 닿으면 빈 값(「뗄 대장이 없다」와 같은 값)이다. 예외로 올리면 서킷이 실패로 세고 사용자
 * 조회가 503 이 되는데, 한도는 제공처가 멀쩡한 상태다. 판정은 대장 없이 진행하고 대장 항목은 확인 불가로 남는다. 저장하지
 * 않으므로 다음 날 조회가 다시 수집한다. 빈 값의 두 뜻을 가려야 하는 쪽(교체 배치)은 {@link #remaining()} 을 함께 본다.
 *
 * <p><b>두 인스턴스</b> — 앱이 두 프로세스로 뜨므로 카운터는 Redis 에 둔다. 키는 서울 날짜
 * {@code external:building-ledger:daily-calls:{yyyy-MM-dd}} 이고 자정(서울)에 새 키로 넘어간다. 만료는
 * {@value #KEY_TTL_DAYS}일 — 날짜가 넘어간 뒤에도 전날 값을 확인할 여유를 두고, 지난 키가 쌓이지 않게 한다.
 *
 * <p><b>원자성</b> — 「현재 값 확인 → 증가 → 첫 증가면 만료」를 Lua 스크립트 하나로 한다. 따로 부르면 두 인스턴스가 같은 값을
 * 보고 함께 상한을 넘고, 증가 뒤 만료를 걸기 전에 죽으면 만료 없는 키가 남는다. 상한에 닿은 뒤의 시도는 세지 않는다.
 *
 * <p>문자열 템플릿을 쓰는 이유는 분산 락과 같다 — 기본 타이핑 직렬화기는 값에 타입 힌트를 붙여 {@code INCR} 이 실패한다.
 */
@Component
public class BuildingLedgerDailyQuota {

    static final String KEY_PREFIX = "external:building-ledger:daily-calls:";
    static final long KEY_TTL_DAYS = 2;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 1 이면 한 칸을 받았다, 0 이면 상한에 닿았다. */
    private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('get', KEYS[1]) or '0')
            if current >= tonumber(ARGV[1]) then
              return 0
            end
            if redis.call('incr', KEYS[1]) == 1 then
              redis.call('expire', KEYS[1], ARGV[2])
            end
            return 1
            """, Long.class);

    /** 상한보다 작으면 상한으로 올린다. 키가 없던 경우에도 만료를 건다. */
    private static final RedisScript<Long> EXHAUST_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('get', KEYS[1]) or '0')
            if current < tonumber(ARGV[1]) then
              redis.call('set', KEYS[1], ARGV[1], 'KEEPTTL')
            end
            if redis.call('ttl', KEYS[1]) < 0 then
              redis.call('expire', KEYS[1], ARGV[2])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final long dailyLimit;
    private final Clock clock;

    @Autowired
    public BuildingLedgerDailyQuota(
            StringRedisTemplate stringRedisTemplate,
            @Value("${external.building-ledger.daily-limit}") long dailyLimit) {
        this(stringRedisTemplate, dailyLimit, Clock.system(SEOUL));
    }

    BuildingLedgerDailyQuota(StringRedisTemplate stringRedisTemplate, long dailyLimit, Clock clock) {
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("external.building-ledger.daily-limit 은 0 이상이어야 한다: " + dailyLimit);
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * 오늘 요청 한 건을 쓴다.
     *
     * @return 받았으면 참. 상한에 닿았으면 거짓 — 이때는 세지 않는다
     */
    public boolean tryAcquire() {
        Long acquired = stringRedisTemplate.execute(ACQUIRE_SCRIPT, List.of(keyOf(today())),
                String.valueOf(dailyLimit), String.valueOf(Duration.ofDays(KEY_TTL_DAYS).toSeconds()));
        return acquired != null && acquired == 1L;
    }

    /**
     * 오늘 몫을 상한까지 채운다. 제공처가 일일 한도 초과({@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR})로 거절했을
     * 때 부른다 — 우리 카운터가 남았다고 봐도 제공처가 막았으므로 그날 더 부르지 않는다. 이미 상한 이상이면 줄이지 않는다.
     */
    public void exhaust() {
        stringRedisTemplate.execute(EXHAUST_SCRIPT, List.of(keyOf(today())),
                String.valueOf(dailyLimit), String.valueOf(Duration.ofDays(KEY_TTL_DAYS).toSeconds()));
    }

    /** 오늘 남은 요청 수. 0 이면 상한에 닿았다. */
    public long remaining() {
        String used = stringRedisTemplate.opsForValue().get(keyOf(today()));
        long count = used == null ? 0 : Long.parseLong(used);
        return Math.max(0, dailyLimit - count);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    static String keyOf(LocalDate date) {
        return KEY_PREFIX + date;
    }
}
