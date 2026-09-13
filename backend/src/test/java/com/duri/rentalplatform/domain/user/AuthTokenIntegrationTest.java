package com.duri.rentalplatform.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * USER-02 로그인·토큰 재발급·로그아웃의 통합 검증.
 *
 * <p>실제 PostgreSQL 17과 Redis 7 컨테이너를 기동해 {@code POST /api/auth/login}·{@code /reissue}·
 * {@code /logout} 요청이 필터체인·인가 규칙·서비스·토큰 보관소까지 전 경로를 지나는지 확인한다. 인메모리
 * 대체를 쓰지 않는다(testing.md §1.1 통합 테스트). 리프레시 토큰의 회전과 폐기는 Redis에 보관된 값으로만
 * 판정되므로 실제 Redis 없이는 회전·폐기 검증이 아무것도 확인하지 못한다.
 *
 * <p>클래스에 {@code @Transactional}을 붙이지 않는 이유는 {@code SignupIntegrationTest}와 같다. 로그인은
 * 최종 로그인 시각을 변경 감지로 갱신하고 그 UPDATE는 커밋 경로에서 확정된다. 테스트가 트랜잭션을 열고
 * 롤백하면 실제 저장 경로가 아닌 것을 확인하게 된다. 대신 각 테스트 앞에서 테이블을 비운다.
 *
 * <p>기대값 근거는 API 명세서(회원·인증)의 토큰 응답과 {@code common/ErrorCode}의 상태 코드이며,
 * {@code expiresIn}의 기대값은 {@code application.yml}의 {@code jwt.access-token-validity}다.
 *
 * <p>소셜 로그인과 다중 인스턴스 토큰 회전은 이 파일의 범위가 아니다. 비밀번호 정책 경계는
 * {@code SignupIntegrationTest}가 덮었고 로그인 요청은 그 제약을 쓰지 않는다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthTokenIntegrationTest {

    /**
     * {@code RefreshTokenStore}가 쓰는 키 {@code refresh:{userId}}에 모두 일치하는 패턴.
     * 준비 단계에서 지울 대상을 고르는 데 쓴다.
     */
    private static final String REFRESH_TOKEN_KEY_PATTERN = "refresh:*";

    private static final String VALID_EMAIL = "tenant@example.com";
    private static final String VALID_PASSWORD = "password123!";
    private static final String VALID_NAME = "김전세";
    private static final String VALID_PHONE = "010-1234-5678";

    private static final String UNREGISTERED_EMAIL = "nobody@example.com";
    private static final String WRONG_PASSWORD = "wrongPassword123!";

    private static final String AUTH_INVALID_CREDENTIAL = "AUTH_INVALID_CREDENTIAL";

    private static final int UNAUTHORIZED = 401;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 액세스 토큰 유효 시간. {@code expiresIn}의 기대값을 1800으로 박지 않고 설정에서 읽는 이유는,
     * 응답과 설정이 같은 값이어야 한다는 것이 이 테스트가 지키는 규칙이기 때문이다. 숫자를 박으면
     * 설정만 바뀔 때 테스트가 먼저 틀린다.
     */
    @Value("${jwt.access-token-validity}")
    Duration accessTokenValidity;

    @BeforeEach
    void prepareSingleSignedUpUser() throws Exception {
        // user_auth가 users를 참조하므로 자식을 앞에 적는다. CASCADE를 붙이는 이유는 wishlist·notification 등
        // 다른 테이블도 users를 참조하기 때문이다 — 그 테이블들이 비어 있어도 제약만으로 TRUNCATE가 거부된다.
        jdbcTemplate.execute("TRUNCATE TABLE user_auth, users RESTART IDENTITY CASCADE");
        clearRefreshTokenKeys();

        // 사용자를 INSERT로 만들지 않고 가입 엔드포인트로 만드는 이유: 비밀번호 해시를 테스트가 직접 만들면
        // 운영 경로가 저장하는 값과 다른 값이 들어가 로그인 성공·실패가 실제와 어긋날 수 있다.
        signUp().andExpect(status().isCreated());
    }

    /**
     * 남은 리프레시 토큰 키를 지운다.
     *
     * <p>테이블을 {@code RESTART IDENTITY}로 비우면 사용자 식별자가 매번 1부터 다시 시작하는데, Redis는
     * 그 초기화에 딸려 오지 않는다. 앞 테스트가 남긴 {@code refresh:1}이 다음 테스트의 사용자 1번 키로
     * 그대로 보이므로, 지우지 않으면 로그아웃·회전 검증이 앞 테스트가 남긴 값을 보고 판정한다.
     *
     * <p>{@code FLUSHDB}로 데이터베이스 전체를 비우지 않고 이 패턴의 키만 지운다. 컨테이너는 이 클래스
     * 전용이 아니므로, 전체를 비우면 같은 Redis를 쓰는 다른 테스트가 넣어 둔 값까지 함께 사라진다.
     */
    private void clearRefreshTokenKeys() {
        Set<String> refreshTokenKeys = stringRedisTemplate.keys(REFRESH_TOKEN_KEY_PATTERN);
        if (refreshTokenKeys != null && !refreshTokenKeys.isEmpty()) {
            stringRedisTemplate.delete(refreshTokenKeys);
        }
    }

    /** 가입 요청 본문. 명세의 JSON과 눈으로 대조되도록 Jackson 직렬화 대신 텍스트 블록으로 쓴다. */
    private String signupRequestBody() {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "name": "%s",
                  "phone": "%s"
                }
                """
                .formatted(VALID_EMAIL, VALID_PASSWORD, VALID_NAME, VALID_PHONE);
    }

    private String loginRequestBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """
                .formatted(email, password);
    }

    private String reissueRequestBody(String refreshToken) {
        return """
                {
                  "refreshToken": "%s"
                }
                """
                .formatted(refreshToken);
    }

    private ResultActions signUp() throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(signupRequestBody()));
    }

    /** 로그인은 공개 경로이므로 Authorization 헤더를 싣지 않는다. */
    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(loginRequestBody(email, password)));
    }

    private ResultActions loginWithValidCredential() throws Exception {
        return login(VALID_EMAIL, VALID_PASSWORD).andExpect(status().isOk());
    }

    /** 재발급도 공개 경로다. 주체는 본문의 리프레시 토큰이 가리키므로 헤더를 싣지 않는다. */
    private ResultActions reissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(reissueRequestBody(refreshToken)));
    }

    private ResultActions logoutWithAccessToken(String accessToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private ResultActions logoutWithoutAccessToken() throws Exception {
        return mockMvc.perform(post("/api/auth/logout"));
    }

    private String responseBodyOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String accessTokenOf(ResultActions result) throws Exception {
        return JsonPath.read(responseBodyOf(result), "$.data.accessToken");
    }

    private String refreshTokenOf(ResultActions result) throws Exception {
        return JsonPath.read(responseBodyOf(result), "$.data.refreshToken");
    }

    private long expiresInOf(ResultActions result) throws Exception {
        Number expiresIn = JsonPath.read(responseBodyOf(result), "$.data.expiresIn");
        return expiresIn.longValue();
    }

    private Map<String, Object> singleUserAuthRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM user_auth");
    }

    @Test
    @DisplayName("올바른 이메일과 비밀번호로 로그인하면 200과 비어 있지 않은 액세스·리프레시 토큰을 받는다")
    void logsInWithValidCredential() throws Exception {
        ResultActions result = login(VALID_EMAIL, VALID_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(accessTokenOf(result)).isNotBlank();
        assertThat(refreshTokenOf(result)).isNotBlank();
    }

    @Test
    @DisplayName("로그인 응답의 tokenType은 Bearer이고 isNewUser는 그 이름 그대로 false다")
    void loginResponseCarriesFixedTokenTypeAndNewUserFlag() throws Exception {
        loginWithValidCredential()
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                // isNewUser가 참이 되는 경로는 소셜 최초 가입뿐이고 그것은 차기 범위라 지금은 항상 거짓이다.
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                // 필드 이름을 그대로 단언하는 이유: Jackson이 boolean 필드의 is 접두를 떼면 newUser로 나가
                // 명세와 어긋나는데, 그 사고는 이름을 직접 확인하는 이 단언에서만 드러난다.
                .andExpect(jsonPath("$.data.newUser").doesNotExist());
    }

    @Test
    @DisplayName("로그인 응답의 expiresIn은 설정한 액세스 토큰 유효 시간을 초로 환산한 값과 같다")
    void loginResponseExpiresInMatchesConfiguredAccessTokenValidity() throws Exception {
        assertThat(expiresInOf(loginWithValidCredential()))
                .isEqualTo(accessTokenValidity.toSeconds());
    }

    @Test
    @DisplayName("가입 직후 NULL이던 last_login_at이 로그인 뒤 채워진다")
    void loginFillsLastLoginAt() throws Exception {
        // 로그인 전 상태를 먼저 단언한다. 그래야 값이 채워진 것이 아래 요청 때문임이 드러난다.
        assertThat(singleUserAuthRow().get("last_login_at")).isNull();

        loginWithValidCredential();

        assertThat(singleUserAuthRow().get("last_login_at")).isNotNull();
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 로그인하면 401 AUTH_INVALID_CREDENTIAL을 반환한다")
    void rejectsLoginWithUnregisteredEmail() throws Exception {
        login(UNREGISTERED_EMAIL, VALID_PASSWORD)
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(AUTH_INVALID_CREDENTIAL));
    }

    @Test
    @DisplayName("가입된 이메일에 틀린 비밀번호로 로그인해도 없는 이메일과 똑같이 401 AUTH_INVALID_CREDENTIAL이다")
    void rejectsLoginWithWrongPasswordUsingTheSameErrorAsUnregisteredEmail() throws Exception {
        // 위 테스트와 상태 코드·오류 코드가 같아야 한다. 둘을 구분해 응답하면 응답만 보고 어떤 이메일이
        // 가입돼 있는지 하나씩 확인할 수 있다. 이 테스트가 그 결정을 지킨다.
        login(VALID_EMAIL, WRONG_PASSWORD)
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(AUTH_INVALID_CREDENTIAL));
    }

    @Test
    @DisplayName("리프레시 토큰으로 재발급하면 200과 새 토큰 두 벌을 받고 리프레시 토큰은 직전 것과 다르다")
    void reissuesTokensAndRotatesRefreshToken() throws Exception {
        String issuedRefreshToken = refreshTokenOf(loginWithValidCredential());

        ResultActions result = reissue(issuedRefreshToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(accessTokenOf(result)).isNotBlank();
        assertThat(refreshTokenOf(result)).isNotBlank().isNotEqualTo(issuedRefreshToken);
    }

    @Test
    @DisplayName("회전으로 밀려난 옛 리프레시 토큰으로 재발급하면 401 AUTH_INVALID_CREDENTIAL이다")
    void rejectsReissueWithRotatedOutRefreshToken() throws Exception {
        String rotatedOutRefreshToken = refreshTokenOf(loginWithValidCredential());
        reissue(rotatedOutRefreshToken).andExpect(status().isOk());

        // 서명은 아직 유효하고 만료도 되지 않았다. 보관된 현재 토큰이 아니라는 것만으로 막혀야 한다.
        reissue(rotatedOutRefreshToken)
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.error.code").value(AUTH_INVALID_CREDENTIAL));
    }

    @Test
    @DisplayName("액세스 토큰을 재발급 요청에 넣으면 토큰 종류 검사에 막혀 401이다")
    void rejectsReissueWithAccessToken() throws Exception {
        String accessToken = accessTokenOf(loginWithValidCredential());

        reissue(accessToken)
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.error.code").value(AUTH_INVALID_CREDENTIAL));
    }

    @Test
    @DisplayName("액세스 토큰을 싣고 로그아웃하면 200과 성공 봉투를 반환한다")
    void logsOutWithAccessToken() throws Exception {
        String accessToken = accessTokenOf(loginWithValidCredential());

        logoutWithAccessToken(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("로그아웃한 뒤 그 리프레시 토큰으로 재발급하면 401 AUTH_INVALID_CREDENTIAL이다")
    void rejectsReissueAfterLogout() throws Exception {
        ResultActions loginResult = loginWithValidCredential();
        String accessToken = accessTokenOf(loginResult);
        String refreshToken = refreshTokenOf(loginResult);

        logoutWithAccessToken(accessToken).andExpect(status().isOk());

        reissue(refreshToken)
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.error.code").value(AUTH_INVALID_CREDENTIAL));
    }

    @Test
    @DisplayName("토큰 없이 로그아웃을 호출하면 공개 접두 아래 경로여도 401이다")
    void rejectsLogoutWithoutAccessToken() throws Exception {
        // /api/auth/**가 공개 경로지만 로그아웃만은 되잠겨 있다. 인가 규칙은 먼저 일치하는 하나로 끝나므로
        // 공개 규칙이 앞에 오도록 순서가 바뀌면 로그아웃이 조용히 열린다. 이 테스트가 그 순서를 지킨다.
        logoutWithoutAccessToken()
                .andExpect(status().is(UNAUTHORIZED))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("로그아웃을 두 번 호출해도 두 번째가 200이다 — 이미 없는 키를 지우는 것은 오류가 아니다")
    void logsOutTwiceWithoutError() throws Exception {
        String accessToken = accessTokenOf(loginWithValidCredential());

        logoutWithAccessToken(accessToken).andExpect(status().isOk());

        logoutWithAccessToken(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
