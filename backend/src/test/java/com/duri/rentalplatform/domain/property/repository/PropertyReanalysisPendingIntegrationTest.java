package com.duri.rentalplatform.domain.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재분석 대기 표시(V18)의 조건부 벌크 UPDATE({@link PropertyRepository#markReanalysisPending} ·
 * {@link PropertyRepository#clearReanalysisPending})를 실제 PostgreSQL 에서 본다.
 *
 * <p>벌크 UPDATE 는 쓰기 트랜잭션이 있어야 하므로 {@link TransactionTemplate} 으로 감싼다. {@code @Transactional} 롤백형 격리를 쓰지
 * 않는다 — 두 번째 확인(JDBC 로 읽는 값)이 같은 트랜잭션 안의 미커밋 값을 보게 되어 「커밋된 뒤 값」을 검증하지 못한다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PropertyReanalysisPendingIntegrationTest {

    private static final long JUDGED_PRICE = 340_000_000L;

    @Autowired
    PropertyRepository propertyRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<Long> propertyIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long propertyId : propertyIds) {
            jdbc.update("DELETE FROM property WHERE property_id = ?", propertyId);
        }
    }

    @Test
    @DisplayName("표시를 세우면 1행을 바꾸고, 이미 서 있으면 0행이다")
    void markChangesOneRowThenNone() {
        long propertyId = insertProperty(JUDGED_PRICE, false);

        int first = inTransaction(() -> propertyRepository.markReanalysisPending(propertyId));
        int second = inTransaction(() -> propertyRepository.markReanalysisPending(propertyId));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(pending(propertyId)).isTrue();
    }

    @Test
    @DisplayName("판정에 쓴 시세가 지금 시세와 같으면 표시를 내린다")
    void clearWhenMarketPriceMatches() {
        long propertyId = insertProperty(JUDGED_PRICE, true);

        int changed = inTransaction(() -> propertyRepository.clearReanalysisPending(propertyId, JUDGED_PRICE));

        assertThat(changed).isEqualTo(1);
        assertThat(pending(propertyId)).isFalse();
    }

    @Test
    @DisplayName("판정하는 사이 시세가 바뀌었으면(판정에 쓴 시세와 다르면) 표시를 내리지 않는다")
    void clearKeepsPendingWhenMarketPriceChangedDuringJudgement() {
        long propertyId = insertProperty(JUDGED_PRICE, true);
        // 판정은 340,000,000 으로 했는데, 그 사이 갱신 배치가 시세를 바꿨다.
        jdbc.update("UPDATE property SET market_price = ? WHERE property_id = ?", 350_000_000L, propertyId);

        int changed = inTransaction(() -> propertyRepository.clearReanalysisPending(propertyId, JUDGED_PRICE));

        assertThat(changed).isZero();
        assertThat(pending(propertyId)).isTrue();
        assertThat(marketPrice(propertyId)).isEqualTo(350_000_000L);
    }

    @Test
    @DisplayName("이미 내려가 있으면 0행이다")
    void clearWhenAlreadyDownChangesNothing() {
        long propertyId = insertProperty(JUDGED_PRICE, false);

        int changed = inTransaction(() -> propertyRepository.clearReanalysisPending(propertyId, JUDGED_PRICE));

        assertThat(changed).isZero();
        assertThat(pending(propertyId)).isFalse();
    }

    @Test
    @DisplayName("트랜잭션이 읽어 둔 매물이 낡았어도 표시를 세우는 쓰기는 다른 열(시세)을 되쓰지 않는다")
    void markDoesNotRewriteOtherColumnsFromStaleEntity() {
        long propertyId = insertProperty(JUDGED_PRICE, false);

        inTransaction(() -> {
            // 이 트랜잭션이 매물을 읽어 영속성 컨텍스트에 둔다(시세 340,000,000).
            propertyRepository.findById(propertyId).orElseThrow();
            // 그 사이 다른 쓰기가 시세를 바꾼다.
            jdbc.update("UPDATE property SET market_price = ? WHERE property_id = ?", 350_000_000L, propertyId);
            return propertyRepository.markReanalysisPending(propertyId);
        });

        assertThat(pending(propertyId)).isTrue();
        assertThat(marketPrice(propertyId)).isEqualTo(350_000_000L);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private boolean pending(long propertyId) {
        return jdbc.queryForObject("SELECT is_reanalysis_pending FROM property WHERE property_id = ?", Boolean.class,
                propertyId);
    }

    private long marketPrice(long propertyId) {
        return jdbc.queryForObject("SELECT market_price FROM property WHERE property_id = ?", Long.class,
                propertyId);
    }

    private long insertProperty(long marketPrice, boolean reanalysisPending) {
        long propertyId = jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude, is_reanalysis_pending)
                VALUES (?, '재분석대기시험구', '김임대', ?, ?, ?, 230000000, 0, ?,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?, ?)
                RETURNING property_id
                """, Long.class,
                "서울특별시 재분석대기시험구 시험로 " + UUID.randomUUID(),
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), marketPrice, new BigDecimal("37.5"),
                new BigDecimal("126.8"), reanalysisPending);
        propertyIds.add(propertyId);
        return propertyId;
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }
}
