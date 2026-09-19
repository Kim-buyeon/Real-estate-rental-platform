package com.duri.rentalplatform.domain.user.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 토큰의 발급 · 소비와 발송 간격을 Redis 에서 수행한다 — API 명세(회원) 1.3.
 *
 * <p><b>원문을 저장하지 않는다.</b> 키는 토큰의 SHA-256 해시다. Redis 가 읽히더라도 거기서 링크를 복원할 수 없다(OWASP Forgot
 * Password Cheat Sheet — 토큰은 해시로 저장). 토큰이 32바이트 난수라 솔트 · 느린 해시가 필요 없다 — 사전 공격이 성립하지 않는다.
 *
 * <p>키는 셋이다.
 * <ul>
 *   <li>{@code password-reset:token:{해시}} → 회원 식별자. 수명 동안만 산다. 소비는 {@code GETDEL} 한 번이라 두 요청이 같은 토큰을
 *       함께 쓸 수 없다({@code StreamTicketStore} 와 같다)</li>
 *   <li>{@code password-reset:user:{회원}} → 그 회원의 현재 토큰 해시. 새로 발급할 때 이전 토큰 키를 지우는 데 쓴다 — 회원별 유효
 *       토큰은 하나다</li>
 *   <li>{@code password-reset:interval:{회원}} → 발송 간격. {@code SET NX} 로 잡고 간격이 지나면 사라진다</li>
 * </ul>
 *
 * <p>간격 키를 이메일이 아니라 회원 식별자로 둔다. 간격을 보는 것은 비밀번호 계정이 확인된 뒤이고, 이메일 하나에 비밀번호 계정은
 * 하나뿐이다({@code UNIQUE(auth_type, provider_id)}). 같은 것을 세면서 이메일을 키 이름에 남기지 않는다.
 *
 * <p>두 프로세스가 같은 토큰을 봐야 하므로 Redis 에 둔다. 요청을 받은 인스턴스와 확정을 받는 인스턴스가 다른 것이 정상 경로다.
 * 문자열 템플릿을 쓰는 이유는 {@link RefreshTokenStore} 와 같다. 이 클래스는 예외를 던지지 않는다 — 무효를 400 으로 바꾸는 것은
 * 서비스의 몫이다.
 */
@Component
public class PasswordResetTokenStore {

    private static final String TOKEN_KEY_PREFIX = "password-reset:token:";
    private static final String USER_KEY_PREFIX = "password-reset:user:";
    private static final String INTERVAL_KEY_PREFIX = "password-reset:interval:";

    /** 토큰 엔트로피. 32바이트(256비트) — 명세 1.3. */
    private static final int TOKEN_BYTES = 32;

    /** 메일 링크의 쿼리에 실리므로 URL 안전 문자만 쓰고 패딩을 뺀다. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    private final StringRedisTemplate stringRedisTemplate;

    private final Duration tokenTtl;

    private final Duration sendInterval;

    public PasswordResetTokenStore(
            StringRedisTemplate stringRedisTemplate,
            @Value("${password-reset.token-ttl}") Duration tokenTtl,
            @Value("${password-reset.send-interval}") Duration sendInterval) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenTtl = tokenTtl;
        this.sendInterval = sendInterval;
    }

    /**
     * 발송 간격을 잡는다. 간격 안에 이미 잡혀 있으면 거짓이다.
     *
     * <p>{@code SET NX} 한 번이라 두 인스턴스에 동시에 들어온 요청 중 하나만 참을 받는다. 그래서 같은 회원의 {@link #issue} 는
     * 간격 안에 둘이 겹치지 않는다.
     */
    public boolean claimSendSlot(Long userId) {
        Boolean claimed = stringRedisTemplate.opsForValue()
                .setIfAbsent(INTERVAL_KEY_PREFIX + userId, "1", sendInterval);
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * 새 토큰을 발급하고 그 회원의 이전 토큰을 무효로 만든다.
     *
     * <p>회원 키를 {@code SET ... GET} 으로 바꾸며 이전 해시를 받아 그 토큰 키를 지운다. 새 토큰 키를 먼저 쓰고 회원 키를 나중에
     * 바꾸므로, 도중에 끊겨도 남는 것은 「새 토큰이 살아 있고 이전 토큰도 수명까지 산다」뿐이다 — 새 토큰이 무효인 채로 남지 않는다.
     *
     * @return 메일에 한 번만 실리는 토큰 원문. 어디에도 저장하지 않는다
     */
    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        String hash = hash(token);

        stringRedisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + hash, String.valueOf(userId), tokenTtl);
        String previousHash = stringRedisTemplate.opsForValue().setGet(USER_KEY_PREFIX + userId, hash, tokenTtl);
        if (previousHash != null && !previousHash.equals(hash)) {
            stringRedisTemplate.delete(TOKEN_KEY_PREFIX + previousHash);
        }
        return token;
    }

    /**
     * 토큰을 쓴다. 성공하면 그 토큰은 사라진다 — 같은 토큰의 두 번째 호출은 비어 있다.
     *
     * <p>회원 키는 지우지 않는다. 가리키는 토큰 키가 이미 없으니 해가 없고 수명으로 사라진다. 지우려고 다시 읽고 비교하면 그 사이에
     * 발급된 새 토큰의 회원 키를 지울 수 있다.
     *
     * @return 토큰의 회원 식별자. 없거나 만료 · 사용됨 · 대체됨이면 빈 값 — 사유를 가르지 않는다
     */
    public Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = stringRedisTemplate.opsForValue().getAndDelete(TOKEN_KEY_PREFIX + hash(token));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    /** 토큰의 SHA-256 을 소문자 16진수로. 키 이름에 쓰이므로 바이트 그대로 두지 않는다. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 자바 구현이 SHA-256 을 갖춰야 한다(MessageDigest 명세). 여기 오면 실행 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
