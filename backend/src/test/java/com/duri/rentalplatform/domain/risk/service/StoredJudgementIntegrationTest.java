package com.duri.rentalplatform.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.TestcontainersConfiguration;
import com.duri.rentalplatform.domain.admin.dto.request.RiskThresholdUpdateRequest;
import com.duri.rentalplatform.domain.admin.service.CriteriaCommandService;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import com.duri.rentalplatform.domain.risk.dto.response.RiskReanalyzeResponse;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장된 판정 조회(RISK-01 · #399)를 실제 PostgreSQL(V21 스키마) · Redis 와 Mock 수집 클라이언트로 통과시킨다.
 *
 * <p>확인하는 것 — (a) V21 열({@code judgement_snapshot} · {@code criteria_fingerprint})에 근거 JSON 이 들어가고 Spring 이 등록한
 * 직렬화기로 읽힌다. (b) 두 번째 조회가 판정하지 않고 그 열의 값을 돌려준다. (c) 근거가 없는 행(V21 이전)은 조회가 채운다.
 * (d) 관리자 기준 수정이 커밋된 뒤 조회가 새 기준으로 다시 판정한다. (e) 재분석 뒤 조회가 그 결과를 돌려준다. (f) 재분석 대기 표시가
 * 선 매물은 저장된 근거를 쓰지 않는다.
 *
 * <p>첫 등급과 보증 가능 여부는 Mock 등기가 매물 식별자로 정하므로 미리 알 수 없다. 그래서 단언은 등급 값이 아니라 어느 경로를 탔는지
 * (행 수 · 열 값 · 응답이 열의 값과 같은지)로 한다. 등급이 확정되는 곳은 깡통전세 선을 아주 낮춰 어떤 매물이든 깡통전세로 만든 뒤다.
 *
 * <p><b>격리</b> — {@code @Transactional} 을 붙이지 않는다. 기준표 캐시 무효화는 기준을 바꾼 트랜잭션이 <b>커밋된 뒤</b> 일어나므로 롤백형
 * 격리에서는 일어나지 않는다. 대신 실제로 커밋하고 테스트가 넣은 행과 바꾼 기준을 끝에 되돌린다. 위험 등급 기준은 시드(V7)의 80 · 70 으로
 * 되돌려 같은 방법(기준 수정 서비스)으로 캐시도 함께 비운다.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StoredJudgementIntegrationTest {

    /** 시드(V7)의 위험 등급 기준. 테스트가 바꾼 뒤 이 값으로 되돌린다. */
    private static final BigDecimal SEED_NEGATIVE_EQUITY = new BigDecimal("80.00");
    private static final BigDecimal SEED_CAUTION = new BigDecimal("70.00");

    @Autowired
    RiskAnalysisCommandService service;

    @Autowired
    RiskReanalysisCommandService reanalysisService;

    @Autowired
    CriteriaCommandService criteriaCommandService;

    @Autowired
    LedgerCommandService ledgerCommandService;

    @Autowired
    JudgementCriteriaCache criteriaCache;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private final List<Long> propertyIds = new ArrayList<>();
    private long adminId;

    @BeforeEach
    void insertAdmin() {
        adminId = jdbc.queryForObject("""
                INSERT INTO users (name, email, role, credit_score)
                VALUES ('저장판정시험관리자', ?, 'ADMIN', 800)
                RETURNING user_id
                """, Long.class, "stored-judgement-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void cleanUp() {
        // 기준을 시드 값으로 되돌린다 — 서비스로 고쳐야 커밋 뒤 캐시 무효화가 함께 일어난다.
        criteriaCommandService.updateRiskThreshold(adminId, new RiskThresholdUpdateRequest(
                SEED_NEGATIVE_EQUITY, SEED_CAUTION, "저장판정시험 복구"));
        jdbc.update("DELETE FROM criteria_change_history WHERE changed_by = ?", adminId);
        jdbc.update("DELETE FROM users WHERE user_id = ?", adminId);
        for (Long propertyId : propertyIds) {
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
            redis.delete("risk:reanalyze:interval:" + propertyId);
        }
    }

    @Test
    @DisplayName("첫 조회는 판정해 근거 JSON · 기준 지문을 V21 열에 적고, 그 JSON 을 직렬화기로 읽으면 응답 근거와 같다")
    void firstGetStoresReadableSnapshot() {
        long propertyId = insertProperty(340_000_000L);

        RiskResponse response = service.findLatestOrAnalyze(propertyId);

        assertThat(countRows(propertyId)).isEqualTo(1);
        Map<String, Object> row = latestRow(propertyId);
        assertThat(row.get("criteria_fingerprint")).isEqualTo(criteriaCache.current().fingerprint());
        assertThat((String) row.get("criteria_fingerprint")).hasSize(64);
        RiskResponse.Judgement stored = jsonMapper.readValue((String) row.get("judgement_snapshot"),
                RiskResponse.Judgement.class);
        assertThat(stored).isEqualTo(response.judgement());
    }

    @Test
    @DisplayName("두 번째 조회는 판정하지 않고 저장된 근거를 돌려준다 — 행 수 그대로, 열의 값을 바꾸면 응답이 따라 바뀐다")
    void secondGetServesStoredJudgement() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.findLatestOrAnalyze(propertyId);
        Map<String, Object> before = latestRow(propertyId);

        RiskResponse second = service.findLatestOrAnalyze(propertyId);

        assertThat(second.judgement()).isEqualTo(first.judgement());
        assertThat(second.analyzedAt()).isNotNull();
        assertThat(countRows(propertyId)).isEqualTo(1);
        assertThat(latestRow(propertyId)).isEqualTo(before);

        // 열의 근거를 표지 값으로 바꾸면 응답이 그 값을 낸다 — 응답이 열에서 왔다는 증거다(다시 계산했다면 원래 값이 나온다).
        RiskResponse.Judgement original = first.judgement();
        RiskResponse.Judgement marked = new RiskResponse.Judgement(original.riskGrade(), original.gradeReason(),
                new BigDecimal("12.34"), original.seniorDebtTotal(), original.isNegativeEquity(),
                original.insuranceEligible(), original.providers(), original.personalConditions(),
                original.rightViolations(), original.warnings(), original.consistency());
        jdbc.update("UPDATE risk_analysis SET judgement_snapshot = ? WHERE property_id = ? AND is_latest",
                jsonMapper.writeValueAsString(marked), propertyId);

        assertThat(service.findLatestOrAnalyze(propertyId).debtRatio()).isEqualByComparingTo("12.34");
    }

    @Test
    @DisplayName("저장된 판정을 돌려줄 때 시세 · 기준일은 지금 매물의 값이다")
    void storedJudgementUsesCurrentPropertyPrice() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.findLatestOrAnalyze(propertyId);

        // 재분석 대기 표시 없이 시세만 바꾼다 — 저장된 근거가 그대로 나가야 한다.
        jdbc.update("UPDATE property SET market_price = 350000000, price_date = DATE '2026-07-15' "
                + "WHERE property_id = ?", propertyId);

        RiskResponse second = service.findLatestOrAnalyze(propertyId);

        assertThat(second.marketPrice()).isEqualTo(350_000_000L);
        assertThat(second.priceDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 15));
        assertThat(second.judgement()).isEqualTo(first.judgement());
        assertThat(countRows(propertyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("근거가 없는 최신 행(V21 이전 행)은 조회가 새 행 없이 근거 · 지문을 채운다")
    void rowWithoutSnapshotIsFilledByGet() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.findLatestOrAnalyze(propertyId);
        jdbc.update("UPDATE risk_analysis SET judgement_snapshot = NULL, criteria_fingerprint = NULL "
                + "WHERE property_id = ?", propertyId);

        RiskResponse second = service.findLatestOrAnalyze(propertyId);

        assertThat(second.judgement()).isEqualTo(first.judgement());
        assertThat(second.analyzedAt()).isNotNull();
        assertThat(countRows(propertyId)).isEqualTo(1);
        Map<String, Object> row = latestRow(propertyId);
        assertThat(row.get("judgement_snapshot")).isNotNull();
        assertThat(row.get("criteria_fingerprint")).isEqualTo(criteriaCache.current().fingerprint());
    }

    @Test
    @DisplayName("저장된 근거 JSON 이 깨졌으면 조회가 예외 없이 다시 판정해 정상 근거로 덮어쓴다")
    void brokenSnapshotIsRewrittenByGet() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.findLatestOrAnalyze(propertyId);
        jdbc.update("UPDATE risk_analysis SET judgement_snapshot = '{not json' WHERE property_id = ?", propertyId);

        RiskResponse second = service.findLatestOrAnalyze(propertyId);

        assertThat(second.judgement()).isEqualTo(first.judgement());
        assertThat(second.analyzedAt()).isNotNull();
        assertThat(jsonMapper.readValue((String) latestRow(propertyId).get("judgement_snapshot"),
                RiskResponse.Judgement.class)).isEqualTo(first.judgement());
    }

    @Test
    @DisplayName("관리자가 위험 등급 기준을 고치면 커밋 뒤 조회가 새 기준으로 다시 판정한다 — 지문이 바뀌고 깡통전세로 나온다")
    void adminCriteriaChangeMakesGetRejudge() {
        long propertyId = insertProperty(340_000_000L);
        service.findLatestOrAnalyze(propertyId);
        String oldFingerprint = (String) latestRow(propertyId).get("criteria_fingerprint");
        assertThat(oldFingerprint).isEqualTo(criteriaCache.current().fingerprint());

        // 깡통전세 선을 1% 로 낮추면 보증금이 시세의 1% 를 넘는 모든 매물이 깡통전세다.
        criteriaCommandService.updateRiskThreshold(adminId, new RiskThresholdUpdateRequest(
                new BigDecimal("1.00"), new BigDecimal("0.50"), "저장판정시험"));

        String newFingerprint = criteriaCache.current().fingerprint();
        assertThat(newFingerprint).isNotEqualTo(oldFingerprint);

        RiskResponse after = service.findLatestOrAnalyze(propertyId);

        assertThat(after.isNegativeEquity()).isTrue();
        assertThat(after.riskGrade()).isEqualTo(RiskGrade.DANGER);
        assertThat(latestRow(propertyId).get("criteria_fingerprint")).isEqualTo(newFingerprint);
        assertThat(latestRow(propertyId).get("risk_grade")).isEqualTo("DANGER");
        // 세 번째 조회는 방금 저장한 깡통전세 근거를 그대로 돌려준다 — 저장된 JSON 에서 깡통전세 표시가 살아 있다.
        RiskResponse stored = service.findLatestOrAnalyze(propertyId);
        assertThat(stored.judgement()).isEqualTo(after.judgement());
        assertThat(stored.isNegativeEquity()).isTrue();
        // 이력 행이 남는다 — 새 판정이 등급을 바꿨다면 2건, 이미 DANGER 였다면 1건.
        assertThat(countRows(propertyId)).isBetween(1, 2);
    }

    @Test
    @DisplayName("재분석 뒤 조회는 재분석 결과를 돌려준다")
    void getReflectsReanalysis() {
        long propertyId = insertProperty(340_000_000L);
        service.findLatestOrAnalyze(propertyId);

        // 시세를 크게 낮추고(재분석 대기 표시 없이) 사용자 재분석을 요청한다 — 깡통전세다.
        jdbc.update("UPDATE property SET market_price = 100000000 WHERE property_id = ?", propertyId);
        RiskReanalyzeResponse reanalysis = reanalysisService.reanalyze(propertyId);

        RiskResponse get = service.findLatestOrAnalyze(propertyId);

        assertThat(get.isNegativeEquity()).isTrue();
        assertThat(get.riskGrade()).isEqualTo(reanalysis.riskGrade());
        assertThat(get.analyzedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(reanalysis.analyzedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(latestRow(propertyId).get("risk_grade")).isEqualTo(reanalysis.riskGrade().name());
    }

    @Test
    @DisplayName("재분석 대기 표시가 선 매물은 저장된 근거를 쓰지 않고 새 시세로 다시 판정한다")
    void reanalysisPendingMakesGetRejudge() {
        long propertyId = insertProperty(340_000_000L);
        service.findLatestOrAnalyze(propertyId);

        jdbc.update("UPDATE property SET market_price = 100000000, is_reanalysis_pending = TRUE "
                + "WHERE property_id = ?", propertyId);

        RiskResponse after = service.findLatestOrAnalyze(propertyId);

        assertThat(after.isNegativeEquity()).isTrue();
        assertThat(latestRow(propertyId).get("risk_grade")).isEqualTo(after.riskGrade().name());
        // 판정이 성공해 기록되면 같은 트랜잭션에서 재분석 대기가 내려간다(시세가 판정에 쓴 값 그대로라서).
        assertThat(reanalysisPending(propertyId)).isFalse();
    }

    @Test
    @DisplayName("대장 교체 뒤 판정이 돌지 못하면 재분석 대기가 남고, 조회가 다시 판정해 근거를 새로 적은 뒤에야 대기가 내려가 저장된 판정이 나간다")
    void ledgerReplacementWithoutJudgementKeepsPendingUntilGetRejudges() {
        long propertyId = insertProperty(340_000_000L);
        RiskResponse first = service.findLatestOrAnalyze(propertyId);
        // 전제 — 첫 판정이 Mock 대장을 수집했고 판정 뒤 대기는 내려가 있다.
        assertThat(jdbc.queryForObject("SELECT data_source FROM building_ledger WHERE property_id = ?", String.class,
                propertyId)).isEqualTo("MOCK");
        assertThat(reanalysisPending(propertyId)).isFalse();

        // 대장 교체는 성공했는데 뒤이은 판정은 실패한 상태 — 교체만 하고 판정을 부르지 않는다.
        boolean replaced = ledgerCommandService.replaceMock(propertyId, buildingHubDocument());

        assertThat(replaced).isTrue();
        assertThat(reanalysisPending(propertyId)).isTrue();
        // 낡은 근거에 표지 값을 심는다 — 저장된 판정이 나가면 표지가, 다시 판정하면 실제 값이 나온다.
        stampMarker(propertyId, first.judgement());
        assertThat(countRows(propertyId)).isEqualTo(1);

        RiskResponse rejudged = service.findLatestOrAnalyze(propertyId);

        assertThat(rejudged.debtRatio()).isNotEqualByComparingTo("12.34");
        assertThat(reanalysisPending(propertyId)).isFalse();
        assertThat(jsonMapper.readValue((String) latestRow(propertyId).get("judgement_snapshot"),
                RiskResponse.Judgement.class)).isEqualTo(rejudged.judgement());

        // 대기가 내려간 뒤에는 저장된 판정이 나간다.
        stampMarker(propertyId, first.judgement());
        assertThat(service.findLatestOrAnalyze(propertyId).debtRatio()).isEqualByComparingTo("12.34");
        assertThat(reanalysisPending(propertyId)).isFalse();
    }

    @Test
    @DisplayName("재분석 대기 매물의 판정은 그 사이 바뀐 시세를 덮지 않는다 — 시세는 그대로, 대기는 내려간다")
    void judgementDoesNotOverwriteChangedMarketPrice() {
        long propertyId = insertProperty(340_000_000L);
        service.findLatestOrAnalyze(propertyId);
        jdbc.update("UPDATE property SET market_price = 350000000, is_reanalysis_pending = TRUE "
                + "WHERE property_id = ?", propertyId);

        RiskResponse response = service.findLatestOrAnalyze(propertyId);

        assertThat(response.marketPrice()).isEqualTo(350_000_000L);
        assertThat(jdbc.queryForObject("SELECT market_price FROM property WHERE property_id = ?", Long.class,
                propertyId)).isEqualTo(350_000_000L);
        assertThat(reanalysisPending(propertyId)).isFalse();
    }

    private boolean reanalysisPending(long propertyId) {
        return jdbc.queryForObject("SELECT is_reanalysis_pending FROM property WHERE property_id = ?", Boolean.class,
                propertyId);
    }

    /** 최신 행의 근거를 부채비율 12.34 표지 값으로 바꾼다 — 응답이 저장된 근거에서 왔는지 다시 계산했는지 가른다. */
    private void stampMarker(long propertyId, RiskResponse.Judgement original) {
        RiskResponse.Judgement marked = new RiskResponse.Judgement(original.riskGrade(), original.gradeReason(),
                new BigDecimal("12.34"), original.seniorDebtTotal(), original.isNegativeEquity(),
                original.insuranceEligible(), original.providers(), original.personalConditions(),
                original.rightViolations(), original.warnings(), original.consistency());
        jdbc.update("UPDATE risk_analysis SET judgement_snapshot = ? WHERE property_id = ? AND is_latest",
                jsonMapper.writeValueAsString(marked), propertyId);
    }

    private static BuildingLedgerDocument buildingHubDocument() {
        return new BuildingLedgerDocument("서울특별시 종로구 동망산길 19 (창신동)", null, "공동주택", "철근콘크리트구조",
                null, new BigDecimal("14544.66"), null, LocalDate.of(1992, 11, 25), null,
                LedgerDataSource.BUILDING_HUB);
    }

    private int countRows(long propertyId) {
        return jdbc.queryForObject("SELECT count(*) FROM risk_analysis WHERE property_id = ?", Integer.class,
                propertyId);
    }

    private Map<String, Object> latestRow(long propertyId) {
        return jdbc.queryForMap("""
                SELECT risk_grade, judgement_snapshot, criteria_fingerprint
                FROM risk_analysis WHERE property_id = ? AND is_latest
                """, propertyId);
    }

    private long insertProperty(long marketPrice) {
        long propertyId = jdbc.queryForObject("""
                INSERT INTO property (address, district, landlord_name, contract_type_code_id,
                    property_type_code_id, status_code_id, deposit, monthly_rent, market_price, price_type,
                    price_date, area_sqm, floor, latitude, longitude)
                VALUES (?, '저장판정시험구', '김임대', ?, ?, ?, 230000000, 0, ?,
                    'ACTUAL_TRANSACTION', DATE '2026-06-30', 42.50, 3, ?, ?)
                RETURNING property_id
                """, Long.class,
                "서울특별시 저장판정시험구 시험로 " + UUID.randomUUID(),
                codeId("CONTRACT_TYPE", "DEPOSIT_ONLY"), codeId("PROPERTY_TYPE", "APARTMENT"),
                codeId("PROPERTY_STATUS", "AVAILABLE"), marketPrice, new BigDecimal("37.5"),
                new BigDecimal("126.8"));
        propertyIds.add(propertyId);
        return propertyId;
    }

    private long codeId(String group, String value) {
        return jdbc.queryForObject("SELECT code_id FROM property_code WHERE code_group = ? AND code_value = ?",
                Long.class, group, value);
    }
}
