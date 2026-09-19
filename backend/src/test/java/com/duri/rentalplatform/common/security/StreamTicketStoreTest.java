package com.duri.rentalplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link StreamTicketStore} 를 실제 Redis 에 대고 확인한다 — 일회성 · 만료 · 키 모양.
 *
 * <p><b>일회성이 이 변경의 핵심이다.</b> 티켓은 URL 에 실려 접근 로그 · 브라우저 기록에 남는다. 남은 티켓으로 두 번째 연결이
 * 열리면 액세스 토큰을 쿼리로 보내던 것과 다를 바가 없다. 소비는 {@code GETDEL} 한 번이라 조회와 삭제 사이에 다른 요청이
 * 끼어들 수 없다.
 *
 * <p>수명은 설정({@code notification.sse.ticket-ttl})에서 읽어 비교한다. 테스트에 숫자를 박으면 설정이 바뀔 때 테스트가 설정 대신
 * 옛 값을 지킨다. 만료 확인만은 설정 수명을 기다릴 수 없으므로 수명이 짧은 보관소를 따로 만들어 쓴다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StreamTicketStoreTest {

    private static final long USER_ID = 900_101L;

    /** 발급을 인증한 액세스 토큰의 만료. JWT 의 exp 와 같은 초 단위다. */
    private static final Instant TOKEN_EXPIRES_AT =
            Instant.now().plus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    StreamTicketStore streamTicketStore;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Value("${notification.sse.ticket-ttl}")
    Duration ticketTtl;

    @Test
    @DisplayName("발급한 티켓으로 주인을 얻고, 키 noti:stream-ticket:{ticket} 의 만료는 설정 수명 이하다")
    void issuedTicketResolvesToOwnerAndExpires() {
        String ticket = streamTicketStore.issue(USER_ID, TOKEN_EXPIRES_AT);

        Long remaining = stringRedisTemplate.getExpire(
                "noti:stream-ticket:" + ticket, TimeUnit.SECONDS);
        assertThat(remaining).isPositive().isLessThanOrEqualTo(ticketTtl.toSeconds());
        assertThat(streamTicketStore.consume(ticket))
                .map(StreamTicketStore.TicketClaims::userId)
                .contains(USER_ID);
    }

    @Test
    @DisplayName("티켓에 담긴 토큰 만료가 그대로 돌아온다 — 연결 수명이 이 값에 묶인다")
    void ticketCarriesTokenExpiry() {
        String ticket = streamTicketStore.issue(USER_ID, TOKEN_EXPIRES_AT);

        assertThat(streamTicketStore.consume(ticket))
                .map(StreamTicketStore.TicketClaims::tokenExpiresAt)
                .contains(TOKEN_EXPIRES_AT);
    }

    @Test
    @DisplayName("티켓에 담긴 토큰 만료가 연결 수명을 제한한다 — 설정 상한보다 짧은 만료가 그대로 돌아온다")
    void ticketExpiryCapsConnectionLifetime() {
        Instant almostExpired = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
        String ticket = streamTicketStore.issue(USER_ID, almostExpired);

        Instant carried = streamTicketStore.consume(ticket).orElseThrow().tokenExpiresAt();

        // 이 값이 연결 수명 계산(NotificationStreamService.timeoutFor)의 입력이다. 설정 상한(30분)보다 짧으므로
        // 연결은 상한이 아니라 토큰 잔여 시간으로 닫힌다 — 헤더 토큰으로 연 연결과 같다.
        assertThat(carried).isEqualTo(almostExpired);
        assertThat(Duration.between(Instant.now(), carried)).isLessThan(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("토큰 만료를 모르면 비워 두고, 연결 수명은 설정 상한을 쓴다")
    void ticketWithoutTokenExpiryIsEmpty() {
        String ticket = streamTicketStore.issue(USER_ID, null);

        StreamTicketStore.TicketClaims claims = streamTicketStore.consume(ticket).orElseThrow();

        assertThat(claims.userId()).isEqualTo(USER_ID);
        assertThat(claims.tokenExpiresAt()).isNull();
    }

    @Test
    @DisplayName("같은 티켓의 두 번째 소비는 비어 있다 — 한 번 쓰이면 사라진다")
    void ticketIsConsumedOnce() {
        String ticket = streamTicketStore.issue(USER_ID, TOKEN_EXPIRES_AT);

        assertThat(streamTicketStore.consume(ticket))
                .map(StreamTicketStore.TicketClaims::userId)
                .contains(USER_ID);
        assertThat(streamTicketStore.consume(ticket)).isEmpty();
        assertThat(stringRedisTemplate.hasKey("noti:stream-ticket:" + ticket)).isFalse();
    }

    @Test
    @DisplayName("만료된 티켓은 비어 있다")
    void expiredTicketIsEmpty() throws InterruptedException {
        StreamTicketStore shortLived = new StreamTicketStore(stringRedisTemplate, Duration.ofSeconds(1));
        String ticket = shortLived.issue(USER_ID, TOKEN_EXPIRES_AT);

        Thread.sleep(1_200);

        assertThat(shortLived.consume(ticket)).isEmpty();
        assertThat(streamTicketStore.consume(ticket)).isEmpty();
    }

    @Test
    @DisplayName("발급한 적 없는 티켓은 비어 있다")
    void unknownTicketIsEmpty() {
        assertThat(streamTicketStore.consume("never-issued-ticket")).isEmpty();
    }

    @Test
    @DisplayName("티켓이 없거나 비어 있으면 Redis 를 보지 않고 비어 있다")
    void missingTicketIsEmpty() {
        assertThat(streamTicketStore.consume(null)).isEmpty();
        assertThat(streamTicketStore.consume("   ")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("발급할 때마다 다른 티켓이다")
    void issuesDistinctTickets() {
        String first = streamTicketStore.issue(USER_ID, TOKEN_EXPIRES_AT);
        String second = streamTicketStore.issue(USER_ID, TOKEN_EXPIRES_AT);

        assertThat(first).isNotEqualTo(second);
        // 앞서 발급한 티켓이 무효가 되지 않는다 — 재연결 요청과 최초 연결이 겹쳐도 둘 다 열린다.
        assertThat(streamTicketStore.consume(first))
                .map(StreamTicketStore.TicketClaims::userId)
                .contains(USER_ID);
        assertThat(streamTicketStore.consume(second))
                .map(StreamTicketStore.TicketClaims::userId)
                .contains(USER_ID);
    }
}
