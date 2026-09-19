package com.duri.rentalplatform.domain.user.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
 * {@link PasswordResetTokenStore} 를 실제 Redis 에 대고 확인한다 — 해시 저장 · 일회성 · 이전 토큰 무효 · 만료 · 발송 간격.
 *
 * <p>수명 · 간격은 설정({@code password-reset.*})에서 읽어 비교한다. 테스트에 숫자를 박으면 설정이 바뀔 때 테스트가 옛 값을
 * 지킨다. 만료 확인만은 설정 수명을 기다릴 수 없으므로 수명이 짧은 보관소를 따로 만든다({@code StreamTicketStoreTest} 와 같다).
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PasswordResetTokenStoreTest {

    private static final long USER_ID = 900_601L;
    private static final long OTHER_USER_ID = 900_602L;

    @Autowired
    PasswordResetTokenStore store;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Value("${password-reset.token-ttl}")
    Duration tokenTtl;

    @Value("${password-reset.send-interval}")
    Duration sendInterval;

    @BeforeEach
    void clean() {
        for (long userId : new long[] {USER_ID, OTHER_USER_ID}) {
            stringRedisTemplate.delete("password-reset:interval:" + userId);
            String hash = stringRedisTemplate.opsForValue().getAndDelete("password-reset:user:" + userId);
            if (hash != null) {
                stringRedisTemplate.delete("password-reset:token:" + hash);
            }
        }
    }

    @Test
    @DisplayName("발급한 토큰은 32바이트 URL 안전 Base64 이고, 소비하면 회원 식별자가 나온다")
    void issuedTokenResolvesToUser() {
        String token = store.issue(USER_ID);

        // 32바이트를 패딩 없이 인코딩하면 43자다.
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(store.consume(token)).contains(USER_ID);
    }

    @Test
    @DisplayName("Redis 에는 원문이 아니라 SHA-256 해시가 키로 남고, 만료는 설정 수명 이하다")
    void storesOnlyHashWithTtl() {
        String token = store.issue(USER_ID);
        String hash = PasswordResetTokenStore.hash(token);

        assertThat(hash).hasSize(64).doesNotContain(token);
        assertThat(stringRedisTemplate.hasKey("password-reset:token:" + token)).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get("password-reset:token:" + hash))
                .isEqualTo(String.valueOf(USER_ID));
        assertThat(stringRedisTemplate.opsForValue().get("password-reset:user:" + USER_ID)).isEqualTo(hash);
        assertThat(stringRedisTemplate.getExpire("password-reset:token:" + hash, TimeUnit.SECONDS))
                .isPositive().isLessThanOrEqualTo(tokenTtl.toSeconds());
        assertThat(stringRedisTemplate.getExpire("password-reset:user:" + USER_ID, TimeUnit.SECONDS))
                .isPositive().isLessThanOrEqualTo(tokenTtl.toSeconds());
    }

    @Test
    @DisplayName("같은 토큰의 두 번째 소비는 비어 있다 — 한 번 쓰면 사라진다")
    void tokenIsConsumedOnce() {
        String token = store.issue(USER_ID);

        assertThat(store.consume(token)).contains(USER_ID);
        assertThat(store.consume(token)).isEmpty();
        assertThat(stringRedisTemplate.hasKey("password-reset:token:" + PasswordResetTokenStore.hash(token)))
                .isFalse();
    }

    @Test
    @DisplayName("새로 발급하면 같은 회원의 이전 토큰은 무효가 되고 새 토큰만 쓰인다")
    void newIssueInvalidatesPreviousToken() {
        String first = store.issue(USER_ID);
        String second = store.issue(USER_ID);

        assertThat(second).isNotEqualTo(first);
        assertThat(store.consume(first)).isEmpty();
        assertThat(store.consume(second)).contains(USER_ID);
    }

    @Test
    @DisplayName("다른 회원의 발급은 내 토큰을 무효로 만들지 않는다")
    void otherUsersIssueDoesNotInvalidate() {
        String mine = store.issue(USER_ID);
        store.issue(OTHER_USER_ID);

        assertThat(store.consume(mine)).contains(USER_ID);
    }

    @Test
    @DisplayName("만료된 토큰은 비어 있다")
    void expiredTokenIsEmpty() throws InterruptedException {
        PasswordResetTokenStore shortLived =
                new PasswordResetTokenStore(stringRedisTemplate, Duration.ofSeconds(1), sendInterval);
        String token = shortLived.issue(USER_ID);

        Thread.sleep(1_200);

        assertThat(shortLived.consume(token)).isEmpty();
    }

    @Test
    @DisplayName("발급한 적 없는 토큰 · 빈 토큰은 비어 있다")
    void unknownOrBlankTokenIsEmpty() {
        assertThat(store.consume("never-issued-token")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
    }

    @Test
    @DisplayName("발송 간격은 처음 한 번만 잡히고, 만료는 설정 간격 이하다")
    void sendSlotIsClaimedOncePerInterval() {
        assertThat(store.claimSendSlot(USER_ID)).isTrue();
        assertThat(store.claimSendSlot(USER_ID)).isFalse();
        assertThat(store.claimSendSlot(OTHER_USER_ID)).isTrue();
        assertThat(stringRedisTemplate.getExpire("password-reset:interval:" + USER_ID, TimeUnit.SECONDS))
                .isPositive().isLessThanOrEqualTo(sendInterval.toSeconds());
    }

    @Test
    @DisplayName("간격이 지나면 다시 잡힌다")
    void sendSlotReopensAfterInterval() throws InterruptedException {
        PasswordResetTokenStore shortInterval =
                new PasswordResetTokenStore(stringRedisTemplate, tokenTtl, Duration.ofSeconds(1));

        assertThat(shortInterval.claimSendSlot(USER_ID)).isTrue();
        Thread.sleep(1_200);

        assertThat(shortInterval.claimSendSlot(USER_ID)).isTrue();
    }
}
