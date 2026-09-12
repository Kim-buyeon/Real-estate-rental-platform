package com.duri.rentalplatform.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.BackendApplication;
import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.domain.user.dto.request.LoginRequest;
import com.duri.rentalplatform.domain.user.dto.request.ReissueRequest;
import com.duri.rentalplatform.domain.user.dto.request.SignupRequest;
import com.duri.rentalplatform.domain.user.dto.response.TokenResponse;
import com.duri.rentalplatform.domain.user.service.RefreshTokenStore;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * USER-02 리프레시 토큰 회전이 인스턴스를 가로질러 성립하는지에 대한 다중 인스턴스 검증.
 *
 * <p>testing.md §1.1의 「다중 인스턴스 테스트」가 대상으로 못박은 셋 가운데 토큰 회전을 담당한다. 리프레시
 * 토큰의 현재 값이 프로세스 메모리에 있으면 인스턴스 A가 회전시킨 사실을 인스턴스 B가 모르고, B에 붙은
 * 요청은 폐기됐어야 할 토큰을 계속 받아 준다. 이 결함은 단일 인스턴스 테스트를 모두 통과하므로 이중화
 * 이후에 발견하면 기능을 다시 작성해야 한다. 이 파일이 확인하는 것은 <b>토큰 상태가 공유 저장소(Redis)에
 * 있다</b>는 사실 자체다.
 *
 * <p>인스턴스를 프로세스 둘로 나누지 않고 같은 JVM 안에 애플리케이션 컨텍스트 둘을 띄운다. 컨텍스트가
 * 다르면 빈도 다르고 따라서 프로세스 메모리에 해당하는 인스턴스 지역 상태도 갈라지므로, 「상태가 각
 * 인스턴스가 아니라 공유 저장소에 있는가」라는 성질은 프로세스를 나누지 않아도 그대로 드러난다. 나누면
 * 기동 시간과 포트만 더 든다.
 *
 * <p>인스턴스 A는 이 테스트 클래스 자신의 컨텍스트이고 인스턴스 B는 {@link SpringApplicationBuilder}로
 * 직접 띄운 컨텍스트다. B에는 A가 주입받은 컨테이너의 접속 정보를 넘겨 <b>같은 PostgreSQL·Redis</b>를
 * 가리키게 한다. B는 HTTP를 받지 않는다 — 확인하려는 것은 B의 토큰 보관소가 A가 쓴 Redis 상태를 보는가이지
 * B의 웹 계층이 아니다. 그래서 B에서는 {@code UserCommandService} 빈을 꺼내 직접 부르고, 거부는 상태
 * 코드가 아니라 {@link BusinessException}으로 드러난다.
 *
 * <p>양쪽을 같은 방식(서비스 빈 직접 호출)으로 부른다. A만 HTTP로 부르면 두 인스턴스의 결과 차이가
 * 인스턴스 때문인지 계층 때문인지 가려지지 않는다.
 *
 * <p>로그인·재발급·로그아웃의 단일 인스턴스 동작과 HTTP 계약은 {@code AuthTokenIntegrationTest}가
 * 덮었으므로 여기서 되풀이하지 않는다. SSE 팬아웃(NOTI-03)과 배치 분산 락(RISK-08)은 각 기능의 몫이다.
 *
 * <p>{@code @Transactional}을 붙이지 않는 이유는 {@code AuthTokenIntegrationTest}와 같고, 여기에는 하나가
 * 더 있다. 인스턴스 B는 자기 데이터소스로 접속하므로 A가 열어 둔 트랜잭션 안의 미커밋 데이터를 볼 수 없다.
 * 대신 각 테스트 앞에서 테이블을 비우고 리프레시 키를 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
// 인스턴스 B 기동은 컨텍스트를 하나 더 올리는 일이라 느리다. 테스트마다 띄우지 않고 클래스에서 한 번만
// 띄우려면 @BeforeAll이 A가 주입받은 컨테이너 정보를 읽어야 하는데, 정적 메서드에서는 주입된 필드를 볼 수
// 없다. 인스턴스 단위 수명으로 두면 테스트 인스턴스가 @BeforeAll보다 먼저 만들어지고 그 시점에 주입이
// 끝나 있으므로 @BeforeAll을 인스턴스 메서드로 쓸 수 있다.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenRotationMultiInstanceTest {

    /** {@code RefreshTokenStore}가 쓰는 키 {@code refresh:{userId}}에 모두 일치하는 패턴. */
    private static final String REFRESH_TOKEN_KEY_PATTERN = "refresh:*";

    private static final int REDIS_EXPOSED_PORT = 6379;

    private static final String VALID_EMAIL = "tenant@example.com";
    private static final String VALID_PASSWORD = "password123!";
    private static final String VALID_NAME = "김전세";
    private static final String VALID_PHONE = "010-1234-5678";

    /** 서명 키 비교에만 쓰는 식별자. 이 값으로 저장소를 조회하지 않으므로 실제 사용자일 필요가 없다. */
    private static final long SIGNATURE_PROBE_USER_ID = 1L;

    @Autowired
    ApplicationContext instanceAContext;

    @Autowired
    UserCommandService instanceAUserCommandService;

    @Autowired
    RefreshTokenStore instanceARefreshTokenStore;

    @Autowired
    JwtTokenProvider instanceAJwtTokenProvider;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    /**
     * {@code PostgreSQLContainer}도 {@code GenericContainer}를 상속하므로 타입만으로는 후보가 둘이다.
     * 빈 이름으로 고른다.
     */
    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    private ConfigurableApplicationContext instanceBContext;
    private UserCommandService instanceBUserCommandService;
    private RefreshTokenStore instanceBRefreshTokenStore;
    private JwtTokenProvider instanceBJwtTokenProvider;

    @BeforeAll
    void startSecondInstance() {
        instanceBContext = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .run(sharedInfrastructureArguments());

        instanceBUserCommandService = instanceBContext.getBean(UserCommandService.class);
        instanceBRefreshTokenStore = instanceBContext.getBean(RefreshTokenStore.class);
        instanceBJwtTokenProvider = instanceBContext.getBean(JwtTokenProvider.class);
    }

    /**
     * 인스턴스 B를 닫는다. 열어 둔 채 두면 B의 커넥션 풀이 컨테이너 커넥션을 계속 붙잡아 뒤따르는 테스트가
     * 커넥션 고갈로 깨진다.
     */
    @AfterAll
    void stopSecondInstance() {
        if (instanceBContext != null) {
            instanceBContext.close();
        }
    }

    /**
     * 인스턴스 B가 A와 같은 PostgreSQL·Redis를 보게 하는 접속 정보.
     *
     * <p>{@code SpringApplicationBuilder.properties()}가 아니라 명령행 인자로 넘긴다. 전자는 기본
     * 프로퍼티로 등록되어 우선순위가 가장 낮으므로, 같은 키를 {@code application.yml}이 갖고 있는 여기서는
     * 설정 파일이 이겨 B가 컨테이너가 아닌 로컬 데이터베이스로 붙는다. 명령행 인자는 설정 파일을 이긴다.
     *
     * <p>Redis 비밀번호를 빈 값으로 덮어쓰는 이유: 컨테이너 Redis에는 비밀번호가 없는데 {@code .env}에
     * {@code REDIS_PASSWORD}가 채워진 환경에서는 B가 그 값으로 AUTH를 보내 연결에 실패한다. 인스턴스 A는
     * {@code @ServiceConnection}이 접속 정보를 통째로 대신하므로 이 문제가 없다.
     */
    private String[] sharedInfrastructureArguments() {
        return new String[] {
                "--spring.datasource.url=" + postgresContainer.getJdbcUrl(),
                "--spring.datasource.username=" + postgresContainer.getUsername(),
                "--spring.datasource.password=" + postgresContainer.getPassword(),
                "--spring.data.redis.host=" + redisContainer.getHost(),
                "--spring.data.redis.port=" + redisContainer.getMappedPort(REDIS_EXPOSED_PORT),
                "--spring.data.redis.password="
        };
    }

    @BeforeEach
    void prepareSingleSignedUpUser() {
        // user_auth가 users를 참조하므로 자식을 앞에 적는다. CASCADE는 users를 참조하는 다른 테이블 때문이다 —
        // 비어 있어도 제약만으로 TRUNCATE가 거부된다.
        jdbcTemplate.execute("TRUNCATE TABLE user_auth, users RESTART IDENTITY CASCADE");
        clearRefreshTokenKeys();

        // 비밀번호 해시를 테스트가 직접 만들지 않는 이유는 형제 파일과 같다 — 운영 경로가 저장하는 값과 다른
        // 값이 들어가면 로그인 성공·실패가 실제와 어긋난다. 다만 가입 엔드포인트 대신 서비스를 직접 부른다.
        // 이 파일은 인스턴스 양쪽을 모두 서비스로 부르므로 준비 절차만 HTTP를 타면 경로가 갈린다.
        instanceAUserCommandService.signUpWithEmail(
                new SignupRequest(VALID_EMAIL, VALID_PASSWORD, VALID_NAME, VALID_PHONE));
    }

    /**
     * 남은 리프레시 토큰 키를 지운다.
     *
     * <p>테이블을 {@code RESTART IDENTITY}로 비우면 사용자 식별자가 1부터 다시 시작하는데 Redis는 그
     * 초기화에 딸려 오지 않는다. 앞 테스트가 남긴 {@code refresh:1}이 다음 테스트의 사용자 1번 키로 그대로
     * 보이므로, 지우지 않으면 회전·폐기 검증이 앞 테스트가 남긴 값을 보고 판정한다. 인스턴스 B가 클래스마다
     * 한 번만 뜨고 테스트 사이에 살아 있어도 남는 상태는 여기서 지우는 이 키와 데이터베이스뿐이다 — 토큰
     * 보관소와 토큰 발급기는 상태를 갖지 않는다.
     *
     * <p>{@code FLUSHDB}를 쓰지 않는 이유도 형제 파일과 같다. 컨테이너는 이 클래스 전용이 아니다.
     */
    private void clearRefreshTokenKeys() {
        Set<String> refreshTokenKeys = stringRedisTemplate.keys(REFRESH_TOKEN_KEY_PATTERN);
        if (refreshTokenKeys != null && !refreshTokenKeys.isEmpty()) {
            stringRedisTemplate.delete(refreshTokenKeys);
        }
    }

    private TokenResponse loginOnInstanceA() {
        return instanceAUserCommandService.login(new LoginRequest(VALID_EMAIL, VALID_PASSWORD));
    }

    private TokenResponse reissueOnInstanceA(String refreshToken) {
        return instanceAUserCommandService.reissue(new ReissueRequest(refreshToken));
    }

    private TokenResponse reissueOnInstanceB(String refreshToken) {
        return instanceBUserCommandService.reissue(new ReissueRequest(refreshToken));
    }

    /** 거부는 HTTP 상태 코드가 아니라 예외로 드러나므로 예외 종류와 함께 ErrorCode까지 단언한다. */
    private void assertRejectedAsInvalidCredential(ThrowingCallable reissueCall) {
        assertThatThrownBy(reissueCall)
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("인스턴스 A와 인스턴스 B는 서로 다른 컨텍스트이고 토큰 보관소·인증 서비스 빈도 서로 다른 인스턴스다")
    void instanceAAndInstanceBAreDistinctContextsWithDistinctBeans() {
        // 이 파일의 나머지 전부가 이 사실을 전제한다. 두 컨텍스트가 같은 객체이거나 같은 빈을 공유하면
        // 아래 테스트들은 한 인스턴스 안에서 일어난 일을 확인하면서 초록으로 남는다.
        assertThat(instanceBContext).isNotSameAs(instanceAContext);
        assertThat(instanceBRefreshTokenStore).isNotSameAs(instanceARefreshTokenStore);
        assertThat(instanceBUserCommandService).isNotSameAs(instanceAUserCommandService);
    }

    @Test
    @DisplayName("인스턴스 A가 발급한 리프레시 토큰을 인스턴스 B가 서명 검증한다 — 두 인스턴스의 서명 키가 같다")
    void bothInstancesShareTheSameJwtSigningKey() {
        // 서명 키가 다르면 A가 발급한 토큰이 B에서 서명 검증에 걸려 거부된다. 그러면 아래 거부 기대들이
        // 전부 통과하지만 확인한 것은 회전이 아니라 키가 다르다는 사실이 된다. 둘 다 같은 application.yml을
        // 읽으므로 기본적으로는 같지만, 그 사실에 기대지 않고 단언으로 고정한다.
        String signingKeyOfInstanceA = instanceAContext.getEnvironment().getProperty("jwt.secret");
        String signingKeyOfInstanceB = instanceBContext.getEnvironment().getProperty("jwt.secret");
        assertThat(signingKeyOfInstanceA).isNotBlank().isEqualTo(signingKeyOfInstanceB);

        // 설정값 비교만으로는 두 발급기가 그 값을 실제로 썼는지 알 수 없으므로 토큰을 건너 검증한다.
        String refreshTokenSignedByInstanceA =
                instanceAJwtTokenProvider.createRefreshToken(SIGNATURE_PROBE_USER_ID);
        assertThat(instanceBJwtTokenProvider.parseRefreshTokenUserId(refreshTokenSignedByInstanceA))
                .isEqualTo(SIGNATURE_PROBE_USER_ID);
    }

    @Test
    @DisplayName("인스턴스 A에서 로그인해 받은 리프레시 토큰으로 인스턴스 B가 재발급하면 성공한다")
    void instanceBReissuesWithRefreshTokenIssuedByInstanceA() {
        // 이 테스트를 거부 기대들보다 먼저 둔다. 아래 셋은 모두 「거부」를 기대하는데, 무엇을 넣어도 거부되는
        // 상태라면 셋이 전부 헛돌아도 초록이다. 「정상 토큰은 B에서도 통한다」를 여기서 먼저 고정해 그
        // 가능성을 막는다. 성공한다는 것은 곧 A가 보관한 토큰을 B가 본다는 뜻이다.
        String refreshTokenFromInstanceA = loginOnInstanceA().refreshToken();

        TokenResponse reissuedByInstanceB = reissueOnInstanceB(refreshTokenFromInstanceA);

        assertThat(reissuedByInstanceB.accessToken()).isNotBlank();
        assertThat(reissuedByInstanceB.refreshToken())
                .isNotBlank()
                .isNotEqualTo(refreshTokenFromInstanceA);
    }

    @Test
    @DisplayName("인스턴스 A가 회전시켜 밀려난 옛 리프레시 토큰으로 인스턴스 B가 재발급하면 AUTH_INVALID_CREDENTIAL로 거부된다")
    void instanceBRejectsRefreshTokenRotatedOutByInstanceA() {
        // 이 파일의 핵심이다. 회전 상태가 A의 메모리에 있었다면 B는 이 토큰을 그대로 받아 준다.
        String rotatedOutRefreshToken = loginOnInstanceA().refreshToken();
        reissueOnInstanceA(rotatedOutRefreshToken);

        // 서명은 유효하고 만료도 되지 않았다. 보관된 현재 토큰이 아니라는 것만으로 막혀야 한다.
        assertRejectedAsInvalidCredential(() -> reissueOnInstanceB(rotatedOutRefreshToken));
    }

    @Test
    @DisplayName("인스턴스 B가 회전시켜 밀려난 옛 리프레시 토큰으로 인스턴스 A가 재발급하면 AUTH_INVALID_CREDENTIAL로 거부된다")
    void instanceARejectsRefreshTokenRotatedOutByInstanceB() {
        // 방향을 바꿔도 같아야 한다. 한쪽만 확인하면 B가 A의 상태를 읽기만 하고 쓰지는 않는 구성도 통과한다.
        String rotatedOutRefreshToken = loginOnInstanceA().refreshToken();
        reissueOnInstanceB(rotatedOutRefreshToken);

        assertRejectedAsInvalidCredential(() -> reissueOnInstanceA(rotatedOutRefreshToken));
    }

    @Test
    @DisplayName("인스턴스 A에서 로그아웃한 뒤 그 리프레시 토큰으로 인스턴스 B가 재발급하면 AUTH_INVALID_CREDENTIAL로 거부된다")
    void instanceBRejectsRefreshTokenRevokedByLogoutOnInstanceA() {
        // 회전뿐 아니라 폐기도 공유돼야 한다. 로그아웃이 A에만 남으면 B로 붙은 요청이 로그아웃한 사용자의
        // 토큰으로 계속 재발급받는다.
        String revokedRefreshToken = loginOnInstanceA().refreshToken();
        // 로그아웃 요청에는 본문이 없고 서버가 아는 것은 인증 주체의 식별자뿐이다. 그 식별자를 데이터베이스에서
        // 다시 조회하지 않고 토큰에서 꺼낸다 — 운영에서 필터가 하는 일과 같다.
        Long userId = instanceAJwtTokenProvider.parseRefreshTokenUserId(revokedRefreshToken);
        instanceAUserCommandService.logout(userId);

        assertRejectedAsInvalidCredential(() -> reissueOnInstanceB(revokedRefreshToken));
    }
}
