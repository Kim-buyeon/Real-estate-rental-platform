package com.duri.rentalplatform.domain.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
import com.duri.rentalplatform.domain.risk.vo.PropertyRefreshReport;
import com.duri.rentalplatform.external.realestate.RentBuildingType;
import com.duri.rentalplatform.external.realestate.RentTransaction;
import com.duri.rentalplatform.external.realestate.RentTransactionClient;
import com.duri.rentalplatform.external.realestate.RentTransactionQuery;
import com.duri.rentalplatform.external.realestate.SaleBuildingType;
import com.duri.rentalplatform.external.realestate.SaleTransaction;
import com.duri.rentalplatform.external.realestate.SaleTransactionClient;
import com.duri.rentalplatform.external.realestate.SaleTransactionQuery;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RISK-08 매물 · 시세 갱신 배치를 실제 PostgreSQL · Redis 로 한 회차 통과시킨다. 외부 연동은 Mock 모드다.
 *
 * <p>확인하는 것은 세 가지다. (a) 적재 태스크릿 스텝 안에서 신규 매물의 JPA 저장과 기존 매물의 시세 갱신이 실제로 커밋되는가 — 스텝이
 * resourceless 트랜잭션 관리자로 도는데 저장 경계는 적재 쓰기 서비스가 긋는다. (b) 시세가 바뀐 관심 매물의 등급이 바뀌면 커밋 뒤
 * 이벤트로 관심 등록자에게만 알림이 생기는가. (c) 최신 판정이 없는 매물이 판정되는가 — 신규 매물과 적재와 무관한 기존 매물 둘 다.
 *
 * <p><b>적재 규모</b> — 전월세 · 매매 실거래가 클라이언트를 테스트용으로 바꿔 끼운다. Mock 클라이언트는 한 회차에 25개 구 × 48건 ×
 * 2개 서비스를 만들어 공유 컨테이너에 2,400건을 넣는다. 여기서는 한 자치구 · 아파트 서비스에만 전월세 일곱 건과 매매 두 건(면적대마다
 * 한 건)을 주고 나머지 조회는 빈 목록이다. 기간(months)도 1개월로 줄인다. 주소 정규화 · 좌표 · 등기 · 대장은 Mock 그대로다.
 *
 * <p><b>격리</b> — 통합 테스트의 기존 방식을 따라 {@code @Transactional} 을 붙이지 않고 실제로 커밋한 뒤 테스트가 넣은 행을 끝에 지운다
 * ({@code NotificationCreationIntegrationTest} 와 같다). 커밋 뒤 비동기 리스너가 알림을 만들어야 하므로 롤백형 격리는 쓸 수 없다.
 * 배치의 판정 스텝은 「최신 판정 없는 매물」 전부를 읽으므로 다른 테스트가 남긴 매물도 함께 판정될 수 있다 — 단언은 이 테스트가 만든
 * 매물 식별자로만 한다.
 *
 * <p><b>등급 변경을 확정하는 방법</b> — 첫 등급은 Mock 등기(매물 식별자에서 결정)가 정하므로 미리 알 수 없다. 시세를 크게 잡은 기존 매물
 * 후보 여럿을 먼저 판정해 두고 등급이 DANGER 가 아닌 첫 후보를 관심 매물로 삼는다. 후보 면적대의 매매 표본을 보증금과 같은 값으로 주어
 * 배치가 시세를 보증금과 같게 낮추면 깡통전세(위험금액 × 100 &gt; 시세 × 80)라 DANGER 가 되므로 등급이 반드시 바뀐다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "property.batch.refresh.months=1",
        "property.batch.refresh.enabled=false",
        "risk.batch.registry-refresh.enabled=false"
})
@Import({TestcontainersConfiguration.class, PropertyRefreshIntegrationTest.NarrowClientConfig.class})
class PropertyRefreshIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String DATE_LOCK_KEY = "property:batch:refresh:" + DATE;
    private static final Duration WAIT = Duration.ofSeconds(15);

    /** 적재 대상 자치구. 실거래가 클라이언트가 이 구의 아파트 조회에만 답한다. */
    private static final String LAWD_CODE = "11110";
    private static final String DISTRICT = "종로구";
    private static final String DONG = "시험동";
    private static final int CANDIDATE_COUNT = 6;

    private static final long STALE_MARKET_PRICE = 1_000_000_000L;
    private static final long CANDIDATE_DEPOSIT = 100_000_000L;
    private static final long NEW_PROPERTY_DEPOSIT = 200_000_000L;
    private static final LocalDate CANDIDATE_CONTRACT_DATE = LocalDate.of(2026, 8, 15);
    private static final LocalDate NEW_CONTRACT_DATE = LocalDate.of(2026, 8, 20);

    /** 후보 면적대(40㎡ 미만)의 매매 표본. 보증금과 같은 값이라 후보는 깡통전세가 된다(클래스 주석). */
    private static final long CANDIDATE_SALE_PRICE = CANDIDATE_DEPOSIT;
    private static final LocalDate CANDIDATE_SALE_DATE = LocalDate.of(2026, 8, 25);

    /** 신규 매물 면적대(40~60㎡)의 매매 표본. 보증금과 다른 값이라 시세가 매매에서 왔는지 드러난다. */
    private static final long NEW_PROPERTY_SALE_PRICE = 400_000_000L;
    private static final LocalDate NEW_PROPERTY_SALE_DATE = LocalDate.of(2026, 8, 28);

    /** 실거래가 클라이언트가 돌려줄 목록. 테스트마다 채운다. */
    private static volatile List<RentTransaction> stubbedTransactions = List.of();

    /** 매매 실거래가 클라이언트가 돌려줄 목록. 테스트마다 채운다. */
    private static volatile List<SaleTransaction> stubbedSales = List.of();

    @Autowired
    PropertyRefreshJobLauncher launcher;

    @Autowired
    RiskAnalysisCommandService riskAnalysisCommandService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> propertyIds = new ArrayList<>();

    @BeforeEach
    void clearDateLock() {
        stringRedisTemplate.delete(DATE_LOCK_KEY);
        stubbedTransactions = List.of();
        stubbedSales = List.of();
    }

    @AfterEach
    void cleanUp() {
        stubbedTransactions = List.of();
        stubbedSales = List.of();
        // 배치가 새로 저장한 매물은 주소로 찾아 지운다.
        for (Long found : jdbc.queryForList("SELECT property_id FROM property WHERE address LIKE ?", Long.class,
                "서울특별시 " + DISTRICT + " " + DONG + " %")) {
            if (!propertyIds.contains(found)) {
                propertyIds.add(found);
            }
        }
        for (Long propertyId : propertyIds) {
            List<Long> notifIds = jdbc.queryForList(
                    "SELECT notif_id FROM wishlist_notification WHERE property_id = ?", Long.class, propertyId);
            jdbc.update("DELETE FROM wishlist_notification WHERE property_id = ?", propertyId);
            notifIds.forEach(notifId -> jdbc.update("DELETE FROM notification WHERE notif_id = ?", notifId));
            jdbc.update("DELETE FROM wishlist WHERE property_id = ?", propertyId);
            jdbc.update("DELETE FROM risk_analysis WHERE property_id = ?", propertyId);
            jdbc.update("""
                    DELETE FROM ownership_history WHERE registry_id IN
                        (SELECT registry_id FROM building_registry WHERE property_id = ?)
                    """, propertyId);
            jdbc.update("""
                    DELETE FROM mortgage_history WHERE registry_id IN
                        (SELECT registry_id FROM building_registry WHERE property_id = ?)
                    """, propertyId);
            jdbc.update("DELETE FROM building_registry WHERE property_id = ?", propertyId);
            jdbc.update("DELETE FROM building_ledger WHERE property_id = ?", propertyId);
            jdbc.update("DELETE FROM property WHERE property_id = ?", propertyId);
        }
        userIds.forEach(userId -> jdbc.update("DELETE FROM users WHERE user_id = ?", userId));
        stringRedisTemplate.delete(DATE_LOCK_KEY);
    }

    @Test
    @DisplayName("적재 스텝이 신규 매물을 저장하고 기존 매물의 시세를 갱신해 커밋한다")
    void loadStepCommitsNewPropertyAndPriceUpdate() {
        List<Long> candidates = insertCandidates();
        long newProperty = newPropertyOf(runWithStubbedTransactions(candidates).report()).id();

        // 신규 매물 — 실거래 한 건이 매물 한 행으로 저장되고 시세는 같은 법정동 · 면적대 매매 표본(한 건)의 중앙값이다.
        Map<String, Object> saved = jdbc.queryForMap("""
                SELECT address, district, deposit, monthly_rent, market_price, price_type, price_date, floor
                FROM property WHERE property_id = ?
                """, newProperty);
        assertThat(saved.get("address")).isEqualTo(newPropertyAddress());
        assertThat(saved.get("district")).isEqualTo(DISTRICT);
        assertThat(saved.get("deposit")).isEqualTo(NEW_PROPERTY_DEPOSIT);
        assertThat(saved.get("monthly_rent")).isEqualTo(0L);
        assertThat(saved.get("market_price")).isEqualTo(NEW_PROPERTY_SALE_PRICE);
        assertThat(saved.get("price_type")).isEqualTo("ACTUAL_TRANSACTION");
        assertThat(saved.get("price_date")).isEqualTo(java.sql.Date.valueOf(NEW_PROPERTY_SALE_DATE));
        assertThat(saved.get("floor")).isEqualTo(5);
        assertThat(countProperties(newPropertyAddress())).isEqualTo(1);

        // 기존 매물 — 시세와 기준일이 새로 계산한 값으로 바뀌고 행은 늘지 않는다.
        for (int index = 0; index < candidates.size(); index++) {
            Map<String, Object> updated = jdbc.queryForMap(
                    "SELECT market_price, price_date FROM property WHERE property_id = ?", candidates.get(index));
            assertThat(updated.get("market_price")).isEqualTo(CANDIDATE_SALE_PRICE);
            assertThat(updated.get("price_date")).isEqualTo(java.sql.Date.valueOf(CANDIDATE_SALE_DATE));
            assertThat(countProperties(candidateAddress(index))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("시세가 바뀐 관심 매물의 등급이 바뀌면 커밋 뒤 이벤트로 모니터링 켜진 관심 등록자에게만 알림이 생긴다")
    void gradeChangeAfterPriceChangeNotifiesOnlyMonitoringWatcher() {
        List<Long> candidates = insertCandidates();
        // 배치 전 판정 — 첫 판정이라 이벤트는 없다. 등급이 DANGER 가 아닌 후보를 관심 매물로 삼는다.
        long watched = -1;
        RiskGrade before = null;
        for (Long candidate : candidates) {
            RiskGrade grade = riskAnalysisCommandService.analyze(candidate).riskGrade();
            if (watched < 0 && grade != RiskGrade.DANGER) {
                watched = candidate;
                before = grade;
            }
        }
        assertThat(watched).as("Mock 등기가 후보 %d개 모두를 DANGER 로 만들 확률은 거의 없다", CANDIDATE_COUNT)
                .isPositive();
        long watchedId = watched;
        long watcher = insertUser();
        long muted = insertUser();
        insertWishlist(watcher, watched, true);
        insertWishlist(muted, watched, false);

        PropertyRefreshReport report = runWithStubbedTransactions(candidates).report();

        assertThat(report.getPriceChangedPropertyIds()).contains(watched);
        awaitUntil(() -> countNotifications(watchedId) == 1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT n.user_id, n.notif_type, wn.change_type, wn.before_value, wn.after_value
                FROM wishlist_notification wn
                JOIN notification n ON n.notif_id = wn.notif_id
                WHERE wn.property_id = ?
                """, watched);
        assertThat(row.get("user_id")).isEqualTo(watcher);
        assertThat(row.get("notif_type")).isEqualTo("RISK_CHANGE");
        assertThat(row.get("change_type")).isEqualTo("RISK_GRADE");
        assertThat(row.get("before_value")).isEqualTo(before.name());
        assertThat(row.get("after_value")).isEqualTo(RiskGrade.DANGER.name());
        // 새 최신 판정에 직전 등급이 남는다.
        assertThat(jdbc.queryForObject(
                "SELECT previous_grade FROM risk_analysis WHERE property_id = ? AND is_latest", String.class, watched))
                .isEqualTo(before.name());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wishlist_notification WHERE property_id = ? AND wish_id IN "
                        + "(SELECT wish_id FROM wishlist WHERE user_id = ?)",
                Integer.class, watched, muted)).isZero();
    }

    @Test
    @DisplayName("최신 판정이 없는 매물은 신규 매물이든 적재와 무관한 기존 매물이든 판정되어 최신 행이 생긴다")
    void propertiesWithoutLatestAnalysisAreJudged() {
        long unrelated = insertProperty("서울특별시 " + DISTRICT + " " + DONG + " 9200-1", STALE_MARKET_PRICE);
        assertThat(latestCount(unrelated)).isZero();

        PropertyRefreshReport report = runWithStubbedTransactions(List.of()).report();

        long newProperty = newPropertyOf(report).id();
        assertThat(latestCount(newProperty)).isEqualTo(1);
        assertThat(latestCount(unrelated)).isEqualTo(1);
        // 처음 판정한 매물이므로 이전 등급이 없다.
        assertThat(jdbc.queryForObject(
                "SELECT previous_grade FROM risk_analysis WHERE property_id = ? AND is_latest", String.class,
                unrelated)).isNull();
        assertThat(report.getFirstAnalyzed()).isGreaterThanOrEqualTo(2);
    }

    // ---- 실행 · 픽스처 ----

    /** 후보 기존 매물 전체와 신규 매물 한 건의 전월세 실거래, 두 면적대의 매매 표본을 클라이언트에 실어 배치를 돌린다. */
    private Run runWithStubbedTransactions(List<Long> candidates) {
        List<RentTransaction> transactions = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            transactions.add(transaction(jibun(index), "30.00", 3, CANDIDATE_DEPOSIT, CANDIDATE_CONTRACT_DATE));
        }
        transactions.add(transaction("9100-1", "50.00", 5, NEW_PROPERTY_DEPOSIT, NEW_CONTRACT_DATE));
        stubbedTransactions = List.copyOf(transactions);
        stubbedSales = List.of(
                sale("30.00", CANDIDATE_SALE_PRICE, CANDIDATE_SALE_DATE),
                sale("50.00", NEW_PROPERTY_SALE_PRICE, NEW_PROPERTY_SALE_DATE));

        PropertyRefreshReport report = launcher.run(DATE);
        assertThat(report.getLoadReport().getFailures()).isEmpty();
        assertThat(report.getLoadReport().getFetched()).isEqualTo(transactions.size());
        return new Run(report);
    }

    private record Run(PropertyRefreshReport report) {
    }

    private record NewProperty(long id) {
    }

    /** 적재가 새로 저장한 매물. 이 테스트가 넣지 않은 행이라 정리 대상에 더한다. */
    private NewProperty newPropertyOf(PropertyRefreshReport report) {
        assertThat(report.getNewProperties()).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT property_id FROM property WHERE address = ?", Long.class,
                newPropertyAddress());
        propertyIds.add(id);
        return new NewProperty(id);
    }

    private static RentTransaction transaction(String jibun, String area, int floor, long deposit,
            LocalDate contractDate) {
        return new RentTransaction(LAWD_CODE, DONG, "시험아파트", jibun, new BigDecimal(area), floor, deposit, 0L,
                contractDate, 2010, RentBuildingType.APARTMENT, "TEST");
    }

    private static SaleTransaction sale(String area, long dealAmount, LocalDate contractDate) {
        return new SaleTransaction(LAWD_CODE, DONG, "시험매매아파트", "9500-1", new BigDecimal(area), 7, dealAmount,
                contractDate, 2010, false, SaleBuildingType.APARTMENT, "TEST");
    }

    private static String jibun(int index) {
        return "%d-1".formatted(9001 + index);
    }

    private static String candidateAddress(int index) {
        return "서울특별시 " + DISTRICT + " " + DONG + " " + jibun(index);
    }

    private static String newPropertyAddress() {
        return "서울특별시 " + DISTRICT + " " + DONG + " 9100-1";
    }

    /** 실거래와 자연키가 같고 시세가 낡은 기존 매물들. 전부 40㎡ 미만 면적대라 같은 매매 표본(후보 면적대 한 건)에서 시세가 잡힌다. */
    private List<Long> insertCandidates() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            ids.add(insertProperty(candidateAddress(index), STALE_MARKET_PRICE));
        }
        return ids;
    }

    private long insertProperty(String address, long marketPrice) {
        long propertyId = jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude)
                VALUES (?, ?, '김임대', ?, ?, ?, ?, 0, ?, 'ACTUAL_TRANSACTION', DATE '2026-01-01', 30.00, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                address, DISTRICT, codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), CANDIDATE_DEPOSIT, marketPrice, new BigDecimal("37.5"),
                new BigDecimal("126.8"));
        propertyIds.add(propertyId);
        return propertyId;
    }

    private long insertUser() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, credit_score) VALUES ('갱신시험', 800) RETURNING user_id", Long.class);
        userIds.add(userId);
        return userId;
    }

    private void insertWishlist(long userId, long propertyId, boolean monitoring) {
        jdbc.update("""
                INSERT INTO wishlist (user_id, property_id, monitoring_yn, alert_condition)
                VALUES (?, ?, ?, 'RISK_AND_REGISTRY')
                """, userId, propertyId, monitoring);
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }

    private int countProperties(String address) {
        return jdbc.queryForObject("SELECT count(*) FROM property WHERE address = ?", Integer.class, address);
    }

    private int latestCount(long propertyId) {
        return jdbc.queryForObject("SELECT count(*) FROM risk_analysis WHERE property_id = ? AND is_latest",
                Integer.class, propertyId);
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

    /** 전월세 · 매매 실거래가 클라이언트를 좁힌다. 한 자치구의 아파트 조회에만 정해진 목록을 주고 나머지는 빈 목록이다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class NarrowClientConfig {

        @Bean
        @Primary
        RentTransactionClient narrowRentTransactionClient() {
            return new RentTransactionClient() {
                @Override
                public List<RentTransaction> findRentTransactions(RentTransactionQuery query) {
                    if (LAWD_CODE.equals(query.lawdCode()) && query.buildingType() == RentBuildingType.APARTMENT) {
                        return stubbedTransactions;
                    }
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        SaleTransactionClient narrowSaleTransactionClient() {
            return new SaleTransactionClient() {
                @Override
                public List<SaleTransaction> findSaleTransactions(SaleTransactionQuery query) {
                    if (LAWD_CODE.equals(query.lawdCode()) && query.buildingType() == SaleBuildingType.APARTMENT) {
                        return stubbedSales;
                    }
                    return List.of();
                }
            };
        }
    }
}
