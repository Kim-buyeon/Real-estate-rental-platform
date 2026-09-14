package com.duri.rentalplatform.domain.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.notification.dto.condition.NotificationListCondition;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.vo.NotificationRow;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotificationMapper} 를 실제 PostgreSQL(Flyway 적용 스키마)에 질의해 확인한다 — testing.md 1.1 매퍼 테스트.
 *
 * <p>픽스처는 테스트가 SQL 로 넣고 테스트마다 롤백한다. 사용자를 테스트마다 새로 만들어 조회가 그 사용자로 좁혀진다.
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class NotificationMapperTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 29, 3, 5, 0);

    @Autowired
    NotificationMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;
    private long propertyId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        propertyId = insertProperty();
    }

    @Test
    @DisplayName("모든 필드가 채워지고 생성 시각은 저장한 서울 벽시계와 같은 시각이다")
    void mapsAllFields() {
        long notificationId = insertNotification(userId, "RISK_CHANGE", false, T0, "CAUTION", "DANGER");

        List<NotificationRow> rows = mapper.selectNotifications(new NotificationListCondition(userId, null, 21));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.notificationId()).isEqualTo(notificationId);
            assertThat(row.type()).isEqualTo(NotificationType.RISK_CHANGE);
            assertThat(row.propertyId()).isEqualTo(propertyId);
            assertThat(row.beforeValue()).isEqualTo("CAUTION");
            assertThat(row.afterValue()).isEqualTo("DANGER");
            assertThat(row.read()).isFalse();
            assertThat(row.createdAt().toInstant()).isEqualTo(T0.atZone(ZoneId.of("Asia/Seoul")).toInstant());
        });
    }

    @Test
    @DisplayName("최신 알림 먼저, 커서 이후만, 한도만큼 — 다른 사용자의 알림은 섞이지 않는다")
    void cursorAndOrderAndUserScope() {
        long first = insertNotification(userId, "RISK_CHANGE", true, T0, "SAFE", "CAUTION");
        long second = insertNotification(userId, "REGISTRY_CHANGE", false, T0.plusMinutes(1), "a", "b");
        long third = insertNotification(userId, "RISK_CHANGE", false, T0.plusMinutes(2), "CAUTION", "DANGER");
        insertNotification(insertUser(), "RISK_CHANGE", false, T0, "SAFE", "DANGER");

        assertThat(mapper.selectNotifications(new NotificationListCondition(userId, null, 2)))
                .extracting(NotificationRow::notificationId).containsExactly(third, second);
        assertThat(mapper.selectNotifications(new NotificationListCondition(userId, second, 21)))
                .extracting(NotificationRow::notificationId).containsExactly(first);
    }

    @Test
    @DisplayName("읽지 않은 수는 그 사용자의 읽지 않은 알림만 센다")
    void countsUnreadOfUserOnly() {
        insertNotification(userId, "RISK_CHANGE", false, T0, "SAFE", "CAUTION");
        insertNotification(userId, "RISK_CHANGE", false, T0, "CAUTION", "DANGER");
        insertNotification(userId, "RISK_CHANGE", true, T0, "DANGER", "SAFE");
        insertNotification(insertUser(), "RISK_CHANGE", false, T0, "SAFE", "DANGER");

        assertThat(mapper.countUnread(userId)).isEqualTo(2L);
    }

    private long insertNotification(long user, String type, boolean read, LocalDateTime createdAt, String before,
            String after) {
        long notificationId = jdbc.queryForObject("""
                INSERT INTO notification (user_id, notif_type, is_read, created_at) VALUES (?, ?, ?, ?)
                RETURNING notif_id
                """, Long.class, user, type, read, createdAt);
        String changeType = "RISK_CHANGE".equals(type) ? "RISK_GRADE" : "REGISTRY";
        jdbc.update("""
                INSERT INTO wishlist_notification (notif_id, property_id, wish_id, change_type, before_value,
                    after_value, detected_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?)
                """, notificationId, propertyId, changeType, before, after, createdAt);
        return notificationId;
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject(
                "SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private long insertProperty() {
        return jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, registered_at)
                VALUES ('서울특별시 테스트1구 시험로 1', '테스트1구', '김임대', ?, ?, ?, 230000000, 0, 340000000,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, 37.5, 126.8, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), T0);
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('시험', 800) RETURNING user_id", Long.class);
    }
}
