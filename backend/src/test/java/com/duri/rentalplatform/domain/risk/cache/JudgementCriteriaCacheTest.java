package com.duri.rentalplatform.domain.risk.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.cache.CriteriaVersionStore;
import com.duri.rentalplatform.common.cache.CriteriaVersionWatcher;
import com.duri.rentalplatform.domain.risk.entity.GuaranteeCriteria;
import com.duri.rentalplatform.domain.risk.entity.RiskCriteria;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.repository.GuaranteeCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.GuaranteePremiumRateRepository;
import com.duri.rentalplatform.domain.risk.repository.HfCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.InsuranceProductRepository;
import com.duri.rentalplatform.domain.risk.repository.RiskCriteriaRepository;
import com.duri.rentalplatform.domain.risk.repository.SgiCriteriaRepository;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.JudgementCriteria;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link JudgementCriteriaCache} — 첫 접근 적재 · 버전 키 확인 재적재({@link CriteriaVersionWatcher} 를 거쳐) · Redis 실패 · 커밋 뒤
 * 무효화 · 무효화와 겹친 적재. 저장소 · Redis · 트랜잭션 관리자는 목이다(컨테이너 없음). 대출 한도 기준은 {@code LoanCriteriaCacheTest}. 읽기 횟수는 위험 등급 기준 저장소 호출 수로 센다 — 적재 한 번이 그것을 한 번 부른다.
 */
class JudgementCriteriaCacheTest {

    private GuaranteeCriteriaRepository guaranteeCriteriaRepository;
    private RiskCriteriaRepository riskCriteriaRepository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private JudgementCriteriaCache cache;
    private CriteriaVersionWatcher watcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guaranteeCriteriaRepository = mock(GuaranteeCriteriaRepository.class);
        riskCriteriaRepository = mock(RiskCriteriaRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        givenRiskCriteria("80.00", "70.00");
        List<GuaranteeCriteria> rows = List.of(
                guarantee(13L, GuaranteeProvider.SGI),
                guarantee(11L, GuaranteeProvider.HUG),
                guarantee(12L, GuaranteeProvider.HF));
        when(guaranteeCriteriaRepository.findAll()).thenReturn(rows);
        cache = newCache("mock");
        watcher = new CriteriaVersionWatcher(new CriteriaVersionStore(redis), List.of(cache), Duration.ofSeconds(1));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private JudgementCriteriaCache newCache(String ledgerMode) {
        return new JudgementCriteriaCache(guaranteeCriteriaRepository, mock(HfCriteriaRepository.class),
                mock(SgiCriteriaRepository.class), mock(GuaranteePremiumRateRepository.class),
                mock(InsuranceProductRepository.class), riskCriteriaRepository, new CriteriaVersionStore(redis),
                mock(PlatformTransactionManager.class), ledgerMode);
    }

    // ---------- 적재 ----------

    @Test
    @DisplayName("첫 접근은 DB 에서 읽어 채우고, 다음 접근은 읽지 않는다")
    void firstAccessLoadsThenServesFromMemory() {
        JudgementCriteria first = cache.current();
        JudgementCriteria second = cache.current();

        assertThat(second).isSameAs(first);
        assertThat(first.negativeEquityRatio()).isEqualByComparingTo("80.00");
        assertThat(first.cautionLeaseRatio()).isEqualByComparingTo("70.00");
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("요청 경로의 current() 는 Redis 를 부르지 않는다")
    void currentDoesNotTouchRedis() {
        cache.current();
        cache.current();

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("기관 기준은 저장소가 어떤 순서로 돌려줘도 HUG → HF → SGI 순이고 기관 ID 를 함께 든다")
    void guaranteeCriteriaAreOrderedByProvider() {
        JudgementCriteria criteria = cache.current();

        assertThat(criteria.guaranteeCriteria()).extracting(GuaranteeCriteriaSnapshot::provider)
                .containsExactly(GuaranteeProvider.HUG, GuaranteeProvider.HF, GuaranteeProvider.SGI);
        assertThat(criteria.guaranteeIdOf(GuaranteeProvider.HUG)).isEqualTo(11L);
        assertThat(criteria.guaranteeIdOf(GuaranteeProvider.HF)).isEqualTo(12L);
        assertThat(criteria.guaranteeIdOf(GuaranteeProvider.SGI)).isEqualTo(13L);
    }

    @Test
    @DisplayName("위험 등급 기준 행이 없으면 시드 결함이라 INTERNAL_ERROR 를 올리고 아무것도 담지 않는다")
    void missingRiskCriteriaIsInternalError() {
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cache.current()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThatThrownBy(() -> cache.current()).isInstanceOf(BusinessException.class);
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("동시에 비어 있는 것을 본 요청들은 한 번만 읽는다")
    void concurrentFirstAccessLoadsOnce() throws Exception {
        RiskCriteria row = riskCriteria("80.00", "70.00");
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenAnswer(invocation -> {
            Thread.sleep(100);
            return Optional.of(row);
        });
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JudgementCriteria>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.current();
                }));
            }
            start.countDown();
            JudgementCriteria first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<JudgementCriteria> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    // ---------- 지문 ----------

    @Test
    @DisplayName("기준이 그대로이면 다시 읽어도 지문이 같다")
    void fingerprintIsStableAcrossReload() {
        String before = cache.current().fingerprint();

        cache.reload();

        assertThat(cache.current().fingerprint()).isEqualTo(before);
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("위험 등급 기준이 바뀌면 다시 읽은 뒤 지문이 바뀐다")
    void riskCriteriaChangeChangesFingerprint() {
        String before = cache.current().fingerprint();
        givenRiskCriteria("75.00", "70.00");

        cache.reload();

        assertThat(cache.current().fingerprint()).isNotEqualTo(before);
        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("대장 연동 모드가 다르면 같은 기준표라도 지문이 다르다")
    void ledgerModeIsPartOfFingerprint() {
        assertThat(newCache("real").current().fingerprint()).isNotEqualTo(newCache("mock").current().fingerprint());
    }

    // ---------- 버전 키 확인 ----------

    @Test
    @DisplayName("버전 키가 바뀌면 다시 읽고, 같으면 읽지 않는다")
    void reloadsOnlyWhenVersionChanges() {
        when(values.get(CriteriaVersionStore.VERSION_KEY)).thenReturn("1");
        watcher.reloadIfVersionChanged();
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();

        givenRiskCriteria("75.00", "70.00");
        when(values.get(CriteriaVersionStore.VERSION_KEY)).thenReturn("2");
        watcher.reloadIfVersionChanged();

        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
        // 값이 바뀐 것을 본 확인이 곧바로 다시 읽었다 — current() 가 또 읽지 않는다.
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("버전 키가 아직 없고 지난번에도 없었으면 읽지 않는다")
    void absentVersionKeyTwiceDoesNotReload() {
        when(values.get(CriteriaVersionStore.VERSION_KEY)).thenReturn(null);

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        verifyNoInteractions(riskCriteriaRepository);
    }

    @Test
    @DisplayName("Redis 에서 버전 키를 읽지 못하면 예외를 올리지 않고 담긴 값을 유지한다")
    void redisReadFailureKeepsCurrentValue() {
        JudgementCriteria before = cache.current();
        when(values.get(CriteriaVersionStore.VERSION_KEY)).thenThrow(new IllegalStateException("redis down"));

        watcher.reloadIfVersionChanged();
        watcher.reloadIfVersionChanged();

        assertThat(cache.current()).isSameAs(before);
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("Redis 가 회복되면 바뀐 버전을 다시 확인해 읽는다")
    void redisRecoveryReloadsOnVersionChange() {
        cache.current();
        when(values.get(CriteriaVersionStore.VERSION_KEY)).thenThrow(new IllegalStateException("redis down"));
        watcher.reloadIfVersionChanged();
        givenRiskCriteria("75.00", "70.00");
        doReturn("5").when(values).get(CriteriaVersionStore.VERSION_KEY);

        watcher.reloadIfVersionChanged();

        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
    }

    // ---------- 안전망 다시 읽기 ----------

    @Test
    @DisplayName("안전망 다시 읽기가 DB 읽기에 실패하면 담긴 값을 유지한다")
    void reloadFailureKeepsCurrentValue() {
        JudgementCriteria before = cache.current();
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenThrow(new IllegalStateException("db"));

        cache.reload();

        assertThat(cache.current()).isSameAs(before);
    }

    @Test
    @DisplayName("안전망 다시 읽기는 비우지 않고 바꿔 끼운다 — 읽는 동안에도 옛 값이 보인다")
    void reloadSwapsWithoutEmptyingFirst() {
        JudgementCriteria before = cache.current();
        List<JudgementCriteria> seenDuringReload = new ArrayList<>();
        RiskCriteria fresh = riskCriteria("75.00", "70.00");
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenAnswer(invocation -> {
            seenDuringReload.add(cache.current());
            return Optional.of(fresh);
        });

        cache.reload();

        assertThat(seenDuringReload).containsExactly(before);
        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
    }

    // ---------- 커밋 뒤 무효화 ----------

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 곧바로 비우고 버전 키를 올린다")
    void invalidateOutsideTransactionActsImmediately() {
        cache.current();

        cache.invalidateAfterCommit();

        verify(values).increment(CriteriaVersionStore.VERSION_KEY);
        cache.current();
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("트랜잭션 안에서 부르면 커밋 전에는 비우지도 버전 키를 올리지도 않는다")
    void invalidateInTransactionWaitsForCommit() {
        cache.current();
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAfterCommit();

        verify(values, never()).increment(any());
        cache.current();
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("커밋 뒤에 비우고 버전 키를 올린다")
    void invalidateInTransactionActsAfterCommit() {
        cache.current();
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAfterCommit();

        runAfterCommit();

        verify(values).increment(CriteriaVersionStore.VERSION_KEY);
        cache.current();
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("롤백되면 비우지도 버전 키를 올리지도 않는다")
    void rollbackDoesNotInvalidate() {
        cache.current();
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAfterCommit();

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(values, never()).increment(any());
        cache.current();
        verify(riskCriteriaRepository, times(1)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("버전 키를 올리지 못해도 예외를 올리지 않고 이 슬롯은 비운다")
    void incrementFailureStillInvalidatesLocalSlot() {
        cache.current();
        when(values.increment(CriteriaVersionStore.VERSION_KEY)).thenThrow(new IllegalStateException("redis down"));

        cache.invalidateAfterCommit();

        cache.current();
        verify(riskCriteriaRepository, times(2)).findFirstByOrderByRiskCriteriaIdAsc();
    }

    @Test
    @DisplayName("무효화와 겹친 적재는 담지 않는다 — 읽는 도중 무효화되면 다음 접근이 다시 읽는다")
    void loadOverlappingInvalidationIsDiscarded() {
        RiskCriteria stale = riskCriteria("80.00", "70.00");
        RiskCriteria fresh = riskCriteria("75.00", "70.00");
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenAnswer(invocation -> {
            // 옛 값을 읽은 뒤 담기 전에 다른 요청이 기준을 바꾸고 무효화한다.
            cache.invalidate();
            return Optional.of(stale);
        });

        JudgementCriteria returnedToCaller = cache.current();

        assertThat(returnedToCaller.negativeEquityRatio()).isEqualByComparingTo("80.00");
        doReturn(Optional.of(fresh)).when(riskCriteriaRepository).findFirstByOrderByRiskCriteriaIdAsc();
        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("무효화와 겹친 다시 읽기도 담지 않는다")
    void reloadOverlappingInvalidationIsDiscarded() {
        cache.current();
        RiskCriteria stale = riskCriteria("80.00", "70.00");
        RiskCriteria fresh = riskCriteria("75.00", "70.00");
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenAnswer(invocation -> {
            cache.invalidate();
            return Optional.of(stale);
        });

        cache.reload();

        doReturn(Optional.of(fresh)).when(riskCriteriaRepository).findFirstByOrderByRiskCriteriaIdAsc();
        // 담지 않았고 무효화로 비어 있으므로, 이 접근이 새 값을 읽는다.
        assertThat(cache.current().negativeEquityRatio()).isEqualByComparingTo("75.00");
    }

    private static void runAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    // ---------- 픽스처 ----------

    private void givenRiskCriteria(String negativeEquityRatio, String cautionLeaseRatio) {
        RiskCriteria row = riskCriteria(negativeEquityRatio, cautionLeaseRatio);
        when(riskCriteriaRepository.findFirstByOrderByRiskCriteriaIdAsc()).thenReturn(Optional.of(row));
    }

    private static RiskCriteria riskCriteria(String negativeEquityRatio, String cautionLeaseRatio) {
        RiskCriteria row = mock(RiskCriteria.class);
        when(row.getNegativeEquityRatio()).thenReturn(new BigDecimal(negativeEquityRatio));
        when(row.getCautionLeaseRatio()).thenReturn(new BigDecimal(cautionLeaseRatio));
        return row;
    }

    private static GuaranteeCriteria guarantee(long id, GuaranteeProvider provider) {
        GuaranteeCriteria row = mock(GuaranteeCriteria.class);
        when(row.getGuaranteeId()).thenReturn(id);
        when(row.getProvider()).thenReturn(provider);
        when(row.getCollateralRatio()).thenReturn(new BigDecimal("90.00"));
        when(row.getMaxDeposit()).thenReturn(700_000_000L);
        return row;
    }
}
