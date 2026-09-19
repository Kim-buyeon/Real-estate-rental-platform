package com.duri.rentalplatform.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.user.store.PasswordResetTokenStore;
import com.duri.rentalplatform.external.mail.MailClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * USER-06 비밀번호 재설정의 전 경로 — 실제 PostgreSQL · Redis 위에서 요청 → 메일 링크 → 확정 → 새 비밀번호 로그인.
 *
 * <p>이 테스트가 확인하는 것은 단위 · 슬라이스가 못 보는 이음매다 — 트랜잭션 밖에서 인증 수단의 회원 식별자를 읽는 것(지연 로딩
 * 프록시), 비동기 발송이 실제로 메일 연동에 닿는 것, 확정이 비밀번호를 커밋하고 리프레시 토큰을 끊는 것. 계정이 있을 때만 이음매가
 * 깨지면 응답 코드로 가입 여부가 드러나므로 성공 경로를 끝까지 따라간다.
 *
 * <p>메일 연동만 목으로 바꿔 링크(토큰 원문)를 받는다. 토큰 원문은 어디에도 저장되지 않으므로 이 방법뿐이다.
 * 데이터는 이 테스트의 이메일만 지운다 — 공유 컨테이너의 다른 테스트 데이터를 비우지 않는다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PasswordResetIntegrationTest {

    private static final String EMAIL = "password-reset-it@example.com";
    private static final String OLD_PASSWORD = "0ldP@ssw0rd!";
    private static final String NEW_PASSWORD = "N3wP@ssw0rd!";
    private static final String TOKEN_PARAM = "token=";

    /** 비동기 발송을 기다리는 상한. 실행기 지연만 덮으면 된다. */
    private static final long ASYNC_WAIT_MILLIS = 5_000;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    MailClient mailClient;

    /** 실제 동작을 그대로 쓰고, Redis 장애 테스트에서만 예외를 심는다. 심은 동작은 테스트가 끝나면 초기화된다. */
    @MockitoSpyBean
    PasswordResetTokenStore tokenStore;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM user_auth WHERE provider_id = ?", EMAIL);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", EMAIL);
    }

    @Test
    @DisplayName("요청 → 링크의 토큰으로 확정 → 옛 비밀번호 · 옛 리프레시 토큰은 거절, 새 비밀번호로 로그인, 토큰 재사용은 400")
    void resetsPasswordEndToEnd() throws Exception {
        post("/api/auth/signup", """
                {"email": "%s", "password": "%s", "name": "홍길동"}
                """.formatted(EMAIL, OLD_PASSWORD)).andExpect(status().isCreated());
        String oldRefreshToken = JsonPath.read(login(OLD_PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.refreshToken");

        post("/api/auth/password-reset", "{\"email\": \"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailClient, timeout(ASYNC_WAIT_MILLIS)).sendPasswordResetLink(eq(EMAIL), link.capture());
        assertThat(link.getValue()).contains("/password-reset/confirm?" + TOKEN_PARAM);
        String token = link.getValue().substring(link.getValue().indexOf(TOKEN_PARAM) + TOKEN_PARAM.length());

        String confirm = "{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, NEW_PASSWORD);
        post("/api/auth/password-reset/confirm", confirm)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        post("/api/auth/password-reset/confirm", confirm)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
        login(OLD_PASSWORD).andExpect(status().isUnauthorized());
        login(NEW_PASSWORD).andExpect(status().isOk());
        post("/api/auth/reissue", "{\"refreshToken\": \"%s\"}".formatted(oldRefreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("가입되지 않은 이메일도 200 · data 없음이고 메일은 나가지 않는다")
    void unknownEmailGetsSameResponseWithoutMail() throws Exception {
        post("/api/auth/password-reset", "{\"email\": \"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(mailClient, after(500).never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    @DisplayName("가입 계정이어도 Redis 보관소가 예외를 던지면 요청 API 는 200 · data 없음이고 메일은 나가지 않는다")
    void storeFailureDoesNotLeakIntoResponse() throws Exception {
        post("/api/auth/signup", """
                {"email": "%s", "password": "%s", "name": "홍길동"}
                """.formatted(EMAIL, OLD_PASSWORD)).andExpect(status().isCreated());
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .when(tokenStore).claimSendSlot(anyLong());

        post("/api/auth/password-reset", "{\"email\": \"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 비동기 쪽이 실제로 보관소에 닿았고, 거기서 난 실패가 응답에 새지 않았다.
        verify(tokenStore, timeout(ASYNC_WAIT_MILLIS)).claimSendSlot(anyLong());
        verify(mailClient, after(500).never()).sendPasswordResetLink(anyString(), anyString());
    }

    private ResultActions login(String password) throws Exception {
        return post("/api/auth/login", "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(EMAIL, password));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
