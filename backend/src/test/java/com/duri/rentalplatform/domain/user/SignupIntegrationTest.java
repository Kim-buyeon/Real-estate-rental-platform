package com.duri.rentalplatform.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * USER-01 이메일 회원 가입의 통합 검증.
 *
 * <p>실제 PostgreSQL 17 컨테이너를 기동해 {@code POST /api/auth/signup} 요청이 필터체인·검증·서비스·저장까지
 * 전 경로를 지나는지 확인한다. H2가 아닌 실제 DB로 검증한다(testing.md §1.1 통합 테스트). 이 기능에는 판정
 * 로직도 MyBatis 매퍼도 없으므로 단위 테스트와 매퍼 테스트의 대상이 아니다.
 *
 * <p>클래스에 {@code @Transactional}을 붙이지 않는 이유: 서비스가 {@code saveAndFlush}와 커밋 경로에
 * 의존한다. 테스트가 트랜잭션을 열고 롤백하면 실제 저장 경로가 아닌 것을 확인하게 된다. 대신 각 테스트
 * 앞에서 테이블을 비운다.
 *
 * <p>기대값 근거는 API 명세서(회원·인증)의 가입 응답과 {@code common/ErrorCode}의 상태 코드이며,
 * 비밀번호 길이 경계는 {@code common/validation/PasswordPolicy}가 정한 8자·64자·72바이트다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SignupIntegrationTest {

    /** PasswordPolicy가 정한 비밀번호 문자 수 하한. 포함 경계다. */
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    /** PasswordPolicy가 정한 비밀번호 문자 수 상한. 포함 경계다. */
    private static final int MAXIMUM_PASSWORD_LENGTH = 64;

    /** PasswordPolicy가 정한 비밀번호 UTF-8 바이트 상한. bcrypt가 받는 입력 한계와 같다. */
    private static final int MAXIMUM_PASSWORD_UTF8_BYTES = 72;

    /** 한글 한 자의 UTF-8 바이트 수. 아래 바이트 초과 비밀번호의 길이를 정한 근거다. */
    private static final int KOREAN_CHARACTER_UTF8_BYTES = 3;

    /**
     * 문자 수 상한(64자)은 지키면서 바이트 상한(72바이트)만 넘기는 비밀번호.
     * 한글 한 자가 UTF-8로 3바이트이므로 25자면 75바이트다 — 25자는 64자 이내이고 75바이트는 72바이트 초과다.
     * 문자 수 검사만으로는 걸러지지 않으므로 이 값이 PasswordPolicy의 바이트 검사를 덮는 유일한 입력이다.
     */
    private static final String OVER_BYTE_LIMIT_PASSWORD = "가".repeat(25);

    /** 문자 수 하한을 정확히 만족하는 8자. */
    private static final String MINIMUM_LENGTH_PASSWORD = "pass1234";

    /** 문자 수 하한에 한 자 모자란 7자. */
    private static final String BELOW_MINIMUM_LENGTH_PASSWORD = "pass123";

    /**
     * 문자 수 상한을 정확히 만족하는 64자.
     * ASCII 한 자는 UTF-8로 1바이트이므로 64자는 64바이트다 — 72바이트 이하라 바이트 검사는 통과하고
     * 문자 수 검사만 겨냥한다. 한글로 만들면 바이트 상한에 먼저 걸려 무엇 때문에 실패했는지 흐려진다.
     */
    private static final String MAXIMUM_LENGTH_PASSWORD = "a".repeat(MAXIMUM_PASSWORD_LENGTH);

    /** 문자 수 상한을 한 자 넘긴 65자. 위와 같은 이유로 ASCII다 — 65바이트라 바이트 검사는 통과한다. */
    private static final String ABOVE_MAXIMUM_LENGTH_PASSWORD =
            "a".repeat(MAXIMUM_PASSWORD_LENGTH + 1);

    private static final String VALID_EMAIL = "tenant@example.com";
    private static final String VALID_PASSWORD = "password123!";
    private static final String VALID_NAME = "김전세";
    private static final String VALID_PHONE = "010-1234-5678";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearUserTables() {
        // user_auth가 users를 참조하므로 자식을 앞에 적는다. CASCADE를 붙이는 이유는 wishlist·notification 등
        // 다른 테이블도 users를 참조하기 때문이다 — 그 테이블들이 비어 있어도 제약만으로 TRUNCATE가 거부된다.
        jdbcTemplate.execute("TRUNCATE TABLE user_auth, users RESTART IDENTITY CASCADE");
    }

    /** 가입 요청 본문. 명세의 JSON과 눈으로 대조되도록 Jackson 직렬화 대신 텍스트 블록으로 쓴다. */
    private String signupRequestBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "name": "%s",
                  "phone": "%s"
                }
                """
                .formatted(email, password, VALID_NAME, VALID_PHONE);
    }

    /**
     * 인증 헤더 없이 가입을 호출한다. {@code /api/auth/**}가 공개 경로이므로 토큰을 싣지 않는다.
     * 인코딩을 UTF-8로 못박는 이유는 한글 비밀번호의 바이트 수가 이 테스트의 기대값이기 때문이다.
     */
    private ResultActions signUp(String requestBody) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(requestBody));
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count != null ? count : 0;
    }

    private Map<String, Object> singleUserRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM users");
    }

    private Map<String, Object> singleUserAuthRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM user_auth");
    }

    @Test
    @DisplayName("정상 가입 요청은 201과 성공 봉투를 반환하고 users와 user_auth에 각 한 행을 남긴다")
    void signsUpWithEmail() throws Exception {
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(countRows("users")).isEqualTo(1);
        assertThat(countRows("user_auth")).isEqualTo(1);
    }

    @Test
    @DisplayName("가입한 사용자의 role은 USER이고 자격 정보는 전부 0과 false로 채워진다")
    void storesUserWithDefaultRoleAndEmptyEligibility() throws Exception {
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD)).andExpect(status().isCreated());

        Map<String, Object> user = singleUserRow();
        assertThat(user.get("name")).isEqualTo(VALID_NAME);
        assertThat(user.get("email")).isEqualTo(VALID_EMAIL);
        assertThat(user.get("phone")).isEqualTo(VALID_PHONE);
        assertThat(user.get("role")).isEqualTo("USER");
        assertThat(user.get("annual_income")).isEqualTo(0L);
        assertThat(user.get("credit_score")).isEqualTo(0);
        assertThat(user.get("existing_loan")).isEqualTo(0L);
        assertThat(user.get("existing_loan_annual_payment")).isEqualTo(0L);
        assertThat(user.get("has_house")).isEqualTo(false);
        assertThat(user.get("own_fund")).isEqualTo(0L);
    }

    @Test
    @DisplayName("가입한 인증 수단의 auth_type은 EMAIL이고 provider_id는 요청의 이메일이다")
    void storesEmailAuthWithEmailAsProviderId() throws Exception {
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD)).andExpect(status().isCreated());

        Map<String, Object> userAuth = singleUserAuthRow();
        assertThat(userAuth.get("auth_type")).isEqualTo("EMAIL");
        assertThat(userAuth.get("provider_id")).isEqualTo(VALID_EMAIL);
        assertThat(userAuth.get("user_id")).isEqualTo(singleUserRow().get("user_id"));
    }

    @Test
    @DisplayName("비밀번호는 평문이 아니라 bcrypt 해시로 저장되고 원문과 대조하면 일치한다")
    void storesPasswordAsBcryptHash() throws Exception {
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD)).andExpect(status().isCreated());

        String passwordHash = (String) singleUserAuthRow().get("password_hash");
        assertThat(passwordHash).isNotEqualTo(VALID_PASSWORD);
        // bcrypt 해시는 $2a$·$2b$·$2y$ 중 하나의 표식과 두 자리 비용, 53자의 salt+digest로 이뤄진다.
        assertThat(passwordHash).matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
        assertThat(passwordEncoder.matches(VALID_PASSWORD, passwordHash)).isTrue();
    }

    @Test
    @DisplayName("같은 이메일로 두 번 가입하면 두 번째는 409 USER_DUPLICATED이고 users에는 한 행만 남는다")
    void rejectsDuplicateEmail() throws Exception {
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD)).andExpect(status().isCreated());

        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("USER_DUPLICATED"))
                .andExpect(jsonPath("$.error.field").value("email"));

        assertThat(countRows("users")).isEqualTo(1);
        assertThat(countRows("user_auth")).isEqualTo(1);
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 INVALID_REQUEST와 위반 필드 email을 반환한다")
    void rejectsMalformedEmail() throws Exception {
        signUp(signupRequestBody("tenant-at-example.com", VALID_PASSWORD))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("email"));

        assertThat(countRows("users")).isZero();
    }

    @Test
    @DisplayName("비밀번호가 하한 8자에 한 자 모자란 7자면 400과 위반 필드 password를 반환한다")
    void rejectsPasswordBelowMinimumLength() throws Exception {
        assertThat(BELOW_MINIMUM_LENGTH_PASSWORD.length()).isEqualTo(MINIMUM_PASSWORD_LENGTH - 1);

        signUp(signupRequestBody(VALID_EMAIL, BELOW_MINIMUM_LENGTH_PASSWORD))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("password"));

        assertThat(countRows("users")).isZero();
    }

    @Test
    @DisplayName("비밀번호가 64자 이내여도 UTF-8 72바이트를 넘으면 400과 위반 필드 password를 반환한다")
    void rejectsPasswordOverUtf8ByteLimit() throws Exception {
        // 이 단언은 입력이 문자 수 검사에는 걸리지 않고 바이트 검사에만 걸린다는 전제를 고정한다.
        assertThat(OVER_BYTE_LIMIT_PASSWORD.length()).isLessThanOrEqualTo(MAXIMUM_PASSWORD_LENGTH);
        assertThat(OVER_BYTE_LIMIT_PASSWORD.getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(OVER_BYTE_LIMIT_PASSWORD.length() * KOREAN_CHARACTER_UTF8_BYTES)
                .isGreaterThan(MAXIMUM_PASSWORD_UTF8_BYTES);

        signUp(signupRequestBody(VALID_EMAIL, OVER_BYTE_LIMIT_PASSWORD))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("password"));

        assertThat(countRows("users")).isZero();
    }

    @Test
    @DisplayName("비밀번호가 정확히 하한 8자면 가입된다 — 하한은 포함 경계다")
    void acceptsPasswordAtMinimumLength() throws Exception {
        assertThat(MINIMUM_LENGTH_PASSWORD.length()).isEqualTo(MINIMUM_PASSWORD_LENGTH);

        signUp(signupRequestBody(VALID_EMAIL, MINIMUM_LENGTH_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(countRows("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("비밀번호가 정확히 상한 64자면 가입된다 — 상한은 포함 경계다")
    void acceptsPasswordAtMaximumLength() throws Exception {
        assertThat(MAXIMUM_LENGTH_PASSWORD.length()).isEqualTo(MAXIMUM_PASSWORD_LENGTH);
        // 이 입력이 바이트 상한이 아니라 문자 수 상한만 보게 한다는 전제를 고정한다.
        assertThat(MAXIMUM_LENGTH_PASSWORD.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(MAXIMUM_PASSWORD_UTF8_BYTES);

        signUp(signupRequestBody(VALID_EMAIL, MAXIMUM_LENGTH_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(countRows("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("비밀번호가 상한 64자를 한 자 넘긴 65자면 400과 위반 필드 password를 반환한다")
    void rejectsPasswordAboveMaximumLength() throws Exception {
        assertThat(ABOVE_MAXIMUM_LENGTH_PASSWORD.length()).isEqualTo(MAXIMUM_PASSWORD_LENGTH + 1);
        assertThat(ABOVE_MAXIMUM_LENGTH_PASSWORD.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(MAXIMUM_PASSWORD_UTF8_BYTES);

        signUp(signupRequestBody(VALID_EMAIL, ABOVE_MAXIMUM_LENGTH_PASSWORD))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("password"));

        assertThat(countRows("users")).isZero();
    }

    @Test
    @DisplayName("가입은 공개 경로이므로 Authorization 헤더 없이 호출해도 401이 아니라 201이다")
    void signupPathIsPublicAndNeedsNoAuthorizationHeader() throws Exception {
        // 토큰을 받기 전에 호출하는 경로이므로 필터체인이 /api/auth/**를 열어 두어야 한다.
        // 헤더를 싣지 않은 요청이 인증 실패 봉투가 아니라 성공 봉투를 받는 것으로 공개 여부를 판정한다.
        signUp(signupRequestBody(VALID_EMAIL, VALID_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
