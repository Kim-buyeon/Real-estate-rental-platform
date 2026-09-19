package com.duri.rentalplatform.common.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 실시간 수신 연결을 여는 일회용 티켓의 발급과 소비 — API 명세서(알림) 1.1.
 *
 * <p>표준 {@code EventSource} 는 헤더를 붙일 수 없어 이 경로만 자격 증명을 쿼리로 받는다. 액세스 토큰을 그대로 실을 수는 없다 —
 * URI 쿼리의 액세스 토큰은 RFC 9700 이 금지한다(RFC 6750 의 SHOULD NOT 에서 강화). 토큰은 프록시 · 접근 로그 · 브라우저 기록에
 * 남고 남은 유효 시간 동안 그대로 쓸 수 있다. 대신 <b>한 번만 쓰이고 수초 안에 사라지는 티켓</b>을 싣는다. 로그에 남더라도 이미
 * 소비되었거나 만료되어 아무것도 열지 못한다.
 *
 * <p><b>값은 회원 식별자와 발급에 쓴 액세스 토큰의 만료 시각이다.</b> 역할은 담지 않는다 — 티켓이 액세스 토큰을 대신할 수 있게
 * 되면 옮겨 놓은 위험이 그대로 돌아온다. 티켓이 여는 것은 수신 연결 하나다.
 *
 * <p>만료 시각을 함께 담는 것은 <b>연결이 로그인 세션보다 오래 살아 있지 않게</b> 하기 위해서다. 담지 않으면 만료 1분 전 토큰으로
 * 받은 티켓이 설정 상한(30분)짜리 연결을 열어, 헤더 토큰으로 연 연결과 수명이 달라진다. 이 값은 필터가 헤더 토큰 경로와 같은
 * 요청 속성에 넣어 같은 연결 수명 계산으로 흘려보낸다.
 *
 * <p><b>티켓 문자열 자체는 불투명하다.</b> 클라이언트에 나가는 것은 난수 문자열이고 만료는 Redis 값 쪽에만 있다 — 티켓에 정보를
 * 실어 보내면 URL 에 다시 정보가 남는다.
 *
 * <p>두 인스턴스가 같은 티켓을 봐야 하므로 Redis 에 둔다. A 에서 발급한 티켓으로 B 에 연결하는 일이 정상 경로다(INF-01 무상태).
 * 소비는 {@code GETDEL} 한 번으로 조회와 삭제를 함께 한다 — 조회 뒤 삭제로 나누면 두 요청이 같은 티켓으로 각각 연결을 연다.
 *
 * <p>문자열 템플릿을 쓰는 이유는 {@code RefreshTokenStore} 와 같다.
 *
 * <p><b>알림 도메인이 아니라 공통 인증에 둔다.</b> 티켓은 인증 자격이고 담는 것은 회원 식별자와 토큰 만료뿐이라 알림에 대해 아무것도
 * 모른다. 소비하는 쪽이 공통 인증 필터라 도메인에 두면 {@code common} 이 {@code domain} 을 참조하는 순환이 생긴다(이슈 #125).
 * 키 접두 {@code noti:} 는 발급 경로가 알림 API 에 있어 붙은 이름이고, 이미 발급된 티켓과 호환되도록 그대로 둔다.
 */
@Component
public class StreamTicketStore {

    private static final String KEY_PREFIX = "noti:stream-ticket:";

    /** 값의 구분자. 티켓 문자열이 아니라 Redis 값 쪽에만 쓰인다. */
    private static final String SEPARATOR = ":";

    /** 티켓 엔트로피. 32바이트(256비트)는 수명 안에 추측할 수 있는 양이 아니다. */
    private static final int TICKET_BYTES = 32;

    /** 쿼리 파라미터로 실리므로 URL 안전 문자만 쓰고 패딩을 뺀다. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    private final StringRedisTemplate stringRedisTemplate;

    private final Duration ticketTtl;

    public StreamTicketStore(
            StringRedisTemplate stringRedisTemplate,
            @Value("${notification.sse.ticket-ttl}") Duration ticketTtl) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ticketTtl = ticketTtl;
    }

    /**
     * 티켓을 만들어 만료와 함께 저장한다. 발급할 때마다 새 티켓이고, 앞서 발급한 티켓을 지우지 않는다 — 재연결 요청과 최초 연결이
     * 겹칠 때 아직 쓰지 않은 티켓을 무효로 만들면 정상 재연결이 실패한다. 쓰이지 않은 티켓은 만료로 사라진다.
     *
     * <p>저장 형식은 {@code {userId}:{토큰 만료 epoch 초}} 다. 만료를 모르면 뒤쪽을 비운다 — 그 티켓으로 연 연결은 설정 상한을 쓴다.
     *
     * @param tokenExpiresAt 발급 요청을 인증한 액세스 토큰의 만료 시각. 알 수 없으면 null
     * @return 클라이언트에 한 번만 전달되는 티켓 문자열
     */
    public String issue(Long userId, Instant tokenExpiresAt) {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = ENCODER.encodeToString(bytes);
        String value = userId + SEPARATOR
                + (tokenExpiresAt == null ? "" : String.valueOf(tokenExpiresAt.getEpochSecond()));
        stringRedisTemplate.opsForValue().set(keyOf(ticket), value, ticketTtl);
        return ticket;
    }

    /**
     * 티켓을 쓴다. 성공하면 그 티켓은 사라진다 — 같은 티켓의 두 번째 호출은 비어 있다.
     *
     * @return 티켓이 담고 있던 것. 없거나 이미 쓰였거나 만료면 빈 값
     */
    public Optional<TicketClaims> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String value = stringRedisTemplate.opsForValue().getAndDelete(keyOf(ticket));
        return value == null ? Optional.empty() : Optional.of(parse(value));
    }

    private static TicketClaims parse(String value) {
        int separator = value.indexOf(SEPARATOR);
        String userId = separator < 0 ? value : value.substring(0, separator);
        String expiresAt = separator < 0 ? "" : value.substring(separator + 1);
        return new TicketClaims(
                Long.valueOf(userId),
                expiresAt.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(expiresAt)));
    }

    /**
     * 티켓이 담고 있는 것. {@code JwtTokenProvider.TokenClaims} 와 같은 자리의 값이므로 같은 모양으로 둔다.
     *
     * @param tokenExpiresAt 발급에 쓴 액세스 토큰의 만료 시각. 모르면 null — 연결 수명은 설정 상한을 쓴다
     */
    public record TicketClaims(Long userId, Instant tokenExpiresAt) {}

    static String keyOf(String ticket) {
        return KEY_PREFIX + ticket;
    }
}
