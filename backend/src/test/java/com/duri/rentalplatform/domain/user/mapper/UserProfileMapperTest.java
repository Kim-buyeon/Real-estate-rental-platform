package com.duri.rentalplatform.domain.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.enums.Role;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserProfileMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class UserProfileMapperTest {

    @Autowired
    UserProfileMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("계정 정보: 모든 필드가 채워지고 role 은 열거로, created_at 은 서울 시각으로 돌아온다")
    void selectAccountMapsAllFields() {
        long userId = insertUser(false);

        ProfileResponse.Account account = mapper.selectAccount(userId);

        assertThat(account).extracting(ProfileResponse.Account::name, ProfileResponse.Account::phone,
                        ProfileResponse.Account::email, ProfileResponse.Account::role)
                .containsExactly("매퍼테스트", "010-0000-0000", "mapper@example.com", Role.ADMIN);
        // 드라이버가 어떤 오프셋으로 돌려주든 응답은 서울 오프셋이어야 한다(API 명세서 공통 규약 일시 표기).
        assertThat(account.createdAt())
                .isEqualTo(OffsetDateTime.of(2026, 7, 1, 9, 12, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("자격 정보: 모든 필드가 컬럼 값으로 채워진다")
    void selectProfileMapsAllFields() {
        long userId = insertUser(false);

        ProfileResponse.Profile profile = mapper.selectProfile(userId);

        assertThat(profile).isEqualTo(
                new ProfileResponse.Profile(42_000_000L, 820, 10_000_000L, 1_200_000L, true, 50_000_000L));
    }

    @Test
    @DisplayName("탈퇴한 사용자는 두 조회 모두 null")
    void deletedUserIsNotSelected() {
        long userId = insertUser(true);

        assertThat(mapper.selectAccount(userId)).isNull();
        assertThat(mapper.selectProfile(userId)).isNull();
    }

    @Test
    @DisplayName("없는 사용자는 두 조회 모두 null")
    void unknownUserIsNotSelected() {
        assertThat(mapper.selectAccount(-1L)).isNull();
        assertThat(mapper.selectProfile(-1L)).isNull();
    }

    private long insertUser(boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO users (name, email, phone, role, annual_income, credit_score, existing_loan,
                                   existing_loan_annual_payment, has_house, own_fund, created_at, deleted_at)
                VALUES ('매퍼테스트', 'mapper@example.com', '010-0000-0000', 'ADMIN', 42000000, 820, 10000000,
                        1200000, TRUE, 50000000, TIMESTAMP '2026-07-01 09:12:00',
                        CASE WHEN ? THEN TIMESTAMP '2026-08-01 00:00:00' END)
                RETURNING user_id
                """, Long.class, deleted);
    }
}
