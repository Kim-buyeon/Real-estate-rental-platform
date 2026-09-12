package com.duri.rentalplatform.domain.user.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰의 보관·회전·폐기를 Redis에서 수행한다.
 *
 * <p>애플리케이션이 두 프로세스로 구동되므로 토큰을 프로세스 메모리에 둘 수 없다. 한쪽에서 발급한 토큰이
 * 다른 쪽에 없으면 재발급이 인스턴스에 따라 성공하거나 실패한다.
 *
 * <p>이 클래스가 아는 것은 Redis뿐이다. 토큰을 만들거나 서명을 검증하는 일은 {@code JwtTokenProvider}가,
 * 저장된 값과 다를 때 401을 낼지 정하는 일은 {@code UserCommandService}가 한다. 여기서는 예외를 던지지 않는다.
 *
 * <p>서비스를 {@code QueryService}·{@code CommandService} 둘로 한정하는 규칙에 이 역할이 들어갈 자리가
 * 없으므로 {@code @Service}가 아닌 {@code @Component}로 둔다.
 */
@Component
public class RefreshTokenStore {

    /** 사용자당 키 하나. 로그아웃 요청에 본문이 없어 서버가 아는 것은 인증 주체의 식별자뿐이다. */
    private static final String KEY_PREFIX = "refresh:";

    /**
     * {@code RedisConfig}의 {@code RedisTemplate<String, Object>}를 쓰지 않는다. 그 빈은 값 직렬화기에
     * 기본 타이핑을 켜 두어 평문 문자열에도 타입 힌트가 붙는다. 리프레시 토큰은 문자열 하나라 그 장치가
     * 필요 없고, 붙으면 저장한 값과 비교하는 값이 어긋난다.
     */
    private final StringRedisTemplate stringRedisTemplate;

    private final Duration refreshTokenValidity;

    public RefreshTokenStore(
            StringRedisTemplate stringRedisTemplate,
            @Value("${jwt.refresh-token-validity}") Duration refreshTokenValidity) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshTokenValidity = refreshTokenValidity;
    }

    /**
     * 재발급도 같은 키에 덮어쓴다. 사용자당 유효한 리프레시 토큰은 하나다.
     *
     * <p>만료 시간을 반드시 함께 건다. 걸지 않으면 토큰이 만료된 뒤에도 키가 남아 로그아웃하지 않은
     * 사용자마다 하나씩 쌓인다. 기간을 코드에 박지 않고 주입받은 설정값을 그대로 쓰는 것은, 설정만 바뀌면
     * 토큰은 만료됐는데 키는 살아 있거나 그 반대가 되기 때문이다.
     */
    public void save(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(keyOf(userId), refreshToken, refreshTokenValidity);
    }

    /**
     * 저장된 값이 없거나 다르면 거짓을 돌려줄 뿐, 그 이상의 조치는 하지 않는다. 옛 토큰을 탈취로 보고
     * 전체를 폐기하면 네트워크 재시도로 같은 토큰을 한 번 더 보낸 정상 사용자가 로그아웃된다.
     */
    public boolean isCurrentToken(Long userId, String refreshToken) {
        String storedToken = stringRedisTemplate.opsForValue().get(keyOf(userId));
        return storedToken != null && storedToken.equals(refreshToken);
    }

    public void delete(Long userId) {
        stringRedisTemplate.delete(keyOf(userId));
    }

    private String keyOf(Long userId) {
        return KEY_PREFIX + userId;
    }
}
