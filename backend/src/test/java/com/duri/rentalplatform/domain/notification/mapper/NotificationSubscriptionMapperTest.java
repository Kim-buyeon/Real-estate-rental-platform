package com.duri.rentalplatform.domain.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.notification.vo.NotificationSubscriptionRow;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotificationSubscriptionMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼
 * 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. 사용자를 테스트마다 새로 만들어 조회가 그 사용자로 좁혀진다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class NotificationSubscriptionMapperTest {

    @Autowired
    NotificationSubscriptionMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
    }

    @Test
    @DisplayName("모든 필드가 채워지고 열거는 상수로 돌아온다(DEPOSIT_ONLY 12자가 V12 길이에 들어간다)")
    void mapsAllFields() {
        insert(userId, "NEW_PROPERTY", "강서구", "DEPOSIT_ONLY", 250_000_000L, true);

        assertThat(mapper.selectByUser(userId)).containsExactly(new NotificationSubscriptionRow(
                SubscriptionType.NEW_PROPERTY, "강서구", ContractType.DEPOSIT_ONLY, 250_000_000L, true));
    }

    @Test
    @DisplayName("조건 없는 행은 세 조건이 null 이고, 행은 식별자 오름차순이다")
    void nullConditionsInInsertOrder() {
        insert(userId, "NEW_PROPERTY", "구로구", null, null, false);
        insert(userId, "NEW_PROPERTY", "강서구", null, null, false);
        insert(userId, "RATE_CHANGE", null, null, null, true);

        assertThat(mapper.selectByUser(userId)).containsExactly(
                new NotificationSubscriptionRow(SubscriptionType.NEW_PROPERTY, "구로구", null, null, false),
                new NotificationSubscriptionRow(SubscriptionType.NEW_PROPERTY, "강서구", null, null, false),
                new NotificationSubscriptionRow(SubscriptionType.RATE_CHANGE, null, null, null, true));
    }

    @Test
    @DisplayName("다른 사용자의 행은 나오지 않는다")
    void onlyOwnRows() {
        insert(insertUser(), "RATE_CHANGE", null, null, null, true);

        assertThat(mapper.selectByUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("V12 유일 인덱스: 조건 없는 같은 유형 두 행(자치구 NULL)은 거부된다")
    void nullsNotDistinct() {
        insert(userId, "WISHLIST_MONITORING", null, null, null, true);

        assertThatThrownBy(() -> insert(userId, "WISHLIST_MONITORING", null, null, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(long user, String type, String district, String contractType, Long depositMax,
            boolean active) {
        jdbc.update("""
                INSERT INTO notification_subscription (user_id, subscription_type, target_district, contract_type,
                    deposit_max, is_active)
                VALUES (?, ?, ?, ?, ?, ?)
                """, user, type, district, contractType, depositMax, active);
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('시험', 800) RETURNING user_id", Long.class);
    }
}
