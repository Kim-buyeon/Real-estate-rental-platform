package com.duri.rentalplatform.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.notification.enums.WishlistChangeType;
import com.duri.rentalplatform.domain.notification.service.NotificationCommandService;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.event.RegistryChangedEvent;
import com.duri.rentalplatform.domain.risk.event.RiskGradeChangedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 생성의 커밋 경계 — 실제 PostgreSQL · Redis 와 실제 트랜잭션 · 비동기 · 분산 락으로 돈다.
 *
 * <p>이벤트는 운영과 같이 쓰기 트랜잭션 안에서 발행한다. 커밋되면 비동기 리스너가 알림을 만들고, 롤백되면 리스너가 불리지 않는다.
 * 리스너가 커밋 뒤 새 트랜잭션({@code REQUIRES_NEW})으로 저장하므로 테스트도 트랜잭션을 걸지 않고 실제로 커밋한다. 그래서 넣은
 * 행은 테스트가 끝나면 지운다.
 *
 * <p><b>롤백 증명</b> — 「안 생겼다」는 기다린 시간만으로는 증명되지 않는다. 롤백한 이벤트 뒤에 커밋한 이벤트(다른 매물)를 보내고
 * 그 알림이 생긴 것을 확인한 뒤에 롤백 쪽이 비어 있는지 본다. 롤백된 이벤트는 비동기 작업 자체가 만들어지지 않는다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationCreationIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 14, 3, 0);

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationCommandService notificationCommandService;

    private TransactionTemplate transaction;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> propertyIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        for (Long propertyId : propertyIds) {
            List<Long> notifIds = jdbc.queryForList(
                    "SELECT notif_id FROM wishlist_notification WHERE property_id = ?", Long.class, propertyId);
            jdbc.update("DELETE FROM wishlist_notification WHERE property_id = ?", propertyId);
            notifIds.forEach(notifId -> jdbc.update("DELETE FROM notification WHERE notif_id = ?", notifId));
            jdbc.update("DELETE FROM wishlist WHERE property_id = ?", propertyId);
            jdbc.update("DELETE FROM property WHERE property_id = ?", propertyId);
        }
        userIds.forEach(userId -> jdbc.update("DELETE FROM users WHERE user_id = ?", userId));
    }

    @Test
    @DisplayName("커밋된 위험 등급 변경은 모니터링 켜진 관심 등록자에게만 알림을 만든다")
    void createsAfterCommit() {
        long propertyId = insertProperty();
        long watching = insertUser();
        long muted = insertUser();
        long wishId = insertWishlist(watching, propertyId, true);
        insertWishlist(muted, propertyId, false);

        transaction.executeWithoutResult(status -> eventPublisher.publishEvent(
                new RiskGradeChangedEvent(propertyId, RiskGrade.CAUTION, RiskGrade.DANGER, AT)));

        awaitUntil(() -> countNotifications(propertyId) == 1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT n.user_id, n.notif_type, n.is_read, wn.wish_id, wn.change_type, wn.before_value,
                       wn.after_value, wn.detected_at
                FROM wishlist_notification wn
                JOIN notification n ON n.notif_id = wn.notif_id
                WHERE wn.property_id = ?
                """, propertyId);
        assertThat(row.get("user_id")).isEqualTo(watching);
        assertThat(row.get("notif_type")).isEqualTo("RISK_CHANGE");
        assertThat(row.get("is_read")).isEqualTo(false);
        assertThat(row.get("wish_id")).isEqualTo(wishId);
        assertThat(row.get("change_type")).isEqualTo("RISK_GRADE");
        assertThat(row.get("before_value")).isEqualTo("CAUTION");
        assertThat(row.get("after_value")).isEqualTo("DANGER");
        assertThat(row.get("detected_at")).isNotNull();
    }

    @Test
    @DisplayName("롤백된 트랜잭션의 이벤트로는 알림이 생기지 않는다")
    void notCreatedOnRollback() {
        long rolledBackProperty = insertProperty();
        long committedProperty = insertProperty();
        long user = insertUser();
        insertWishlist(user, rolledBackProperty, true);
        insertWishlist(user, committedProperty, true);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RiskGradeChangedEvent(rolledBackProperty, RiskGrade.SAFE,
                    RiskGrade.DANGER, AT));
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> eventPublisher.publishEvent(
                new RegistryChangedEvent(committedProperty, "갑구 1 · 을구 0", "갑구 1 · 을구 1", AT)));

        awaitUntil(() -> countNotifications(committedProperty) == 1);
        assertThat(countNotifications(rolledBackProperty)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT change_type FROM wishlist_notification WHERE property_id = ?", String.class,
                committedProperty))
                .isEqualTo("REGISTRY");
    }

    @Test
    @DisplayName("같은 변동으로 두 번 생성해도 알림은 하나다 — 중복 방지 키(Redis) · 분산 락 · 새 트랜잭션을 실제로 지난다")
    void sameChangeTwiceCreatesOnce() {
        long propertyId = insertProperty();
        long user = insertUser();
        insertWishlist(user, propertyId, true);

        // 순서를 통제하려고 리스너를 거치지 않고 생성 서비스를 직접 부른다. 비동기 작업끼리는 끝나는 순서가 보장되지 않는다.
        int first = notificationCommandService.createForWishlist(propertyId, WishlistChangeType.RISK_GRADE, "SAFE",
                "CAUTION");
        int second = notificationCommandService.createForWishlist(propertyId, WishlistChangeType.RISK_GRADE, "SAFE",
                "CAUTION");
        int nextChange = notificationCommandService.createForWishlist(propertyId, WishlistChangeType.RISK_GRADE,
                "CAUTION", "DANGER");

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(nextChange).isEqualTo(1);
        assertThat(countNotifications(propertyId)).isEqualTo(2);
    }

    @Test
    @DisplayName("관심 해제로 관심 매물 행이 지워져도 알림 이력은 매물을 가리킨 채 남는다 (V13 SET NULL)")
    void historySurvivesWishlistRemoval() {
        long propertyId = insertProperty();
        long user = insertUser();
        insertWishlist(user, propertyId, true);
        transaction.executeWithoutResult(status -> eventPublisher.publishEvent(
                new RiskGradeChangedEvent(propertyId, RiskGrade.DANGER, RiskGrade.SAFE, AT)));
        awaitUntil(() -> countNotifications(propertyId) == 1);

        jdbc.update("DELETE FROM wishlist WHERE user_id = ? AND property_id = ?", user, propertyId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT wish_id, property_id FROM wishlist_notification WHERE property_id = ?", propertyId);
        assertThat(row.get("wish_id")).isNull();
        assertThat(row.get("property_id")).isEqualTo(propertyId);
    }

    private int countNotifications(long propertyId) {
        return jdbc.queryForObject("SELECT count(*) FROM wishlist_notification WHERE property_id = ?",
                Integer.class, propertyId);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("비동기 알림 생성이 " + WAIT + " 안에 끝나지 않았다");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("대기 중 인터럽트");
            }
        }
    }

    private long insertUser() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('알림시험', 800) RETURNING user_id", Long.class);
        userIds.add(userId);
        return userId;
    }

    private long insertWishlist(long userId, long propertyId, boolean monitoring) {
        return jdbc.queryForObject("""
                INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition)
                VALUES (?, ?, ?, 'RISK_AND_REGISTRY')
                RETURNING wish_id
                """, Long.class, userId, propertyId, monitoring);
    }

    private long insertProperty() {
        long propertyId = jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude)
                VALUES ('서울특별시 알림시험구 시험로 1', '알림시험구', '김임대',
                    ?, ?, ?, 230000000, 0, 340000000, 'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), new BigDecimal("37.5"), new BigDecimal("126.8"));
        propertyIds.add(propertyId);
        return propertyId;
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }
}
