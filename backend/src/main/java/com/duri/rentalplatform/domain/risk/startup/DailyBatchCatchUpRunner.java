package com.duri.rentalplatform.domain.risk.startup;

import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.scheduler.MockLedgerReplaceScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.PropertyRefreshScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.RegistryRefreshScheduler;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 하루 한 번 도는 위험도 배치(RISK-08 · PROP-04)의 기동 뒤 따라잡기. 노드를 작업할 때만 켜는 운용이라 예약 시각(00:30 · 03:00 ·
 * 04:30)에 꺼져 있으면 그날 회차가 빠진다. 기동을 마치면 한 번, <b>오늘(서울) 성공 기록이 없는 배치만</b> {@link DailyBatch} 선언
 * 순서대로 돌린다 — 매물 · 시세 갱신 → 관심 매물 등기 재조회 → Mock 대장 교체.
 *
 * <p><b>기존 진입점을 그대로 부른다</b> — 각 배치의 스케줄러 메서드를 부르므로 날짜 락과 락 경합 처리가 예약 실행과 같다. 켜고 끄는
 * 조건(enabled 설정, Mock 대장 교체는 대장 연동 real)도 스케줄러 빈이 뜨는 조건 그대로다 — 빈이 없으면 그 배치는 건너뛴다.
 *
 * <p><b>하루 한 번</b> — 오늘 이미 성공한 배치는 다시 시작하지 않는다. 외부 호출 한도가 하루 단위라 기동이 잦아도 호출이 늘지 않는다.
 * 실패한 날은 기록이 없으므로 다음 기동이나 다음 예약 시각에 다시 돈다.
 *
 * <p><b>락 경합은 다시 확인한다</b> — 스케줄러가 「다른 인스턴스가 실행 중」으로 건너뛰면(진입점이 false 를 돌려준다) 그 배치를
 * {@code batch.startup-catchup.retry-interval} 마다 다시 확인한다. 회차 도중 노드를 멈추면(배포 · OOM) 해제가 돌지 않아 날짜
 * 락이 남는다 — 예전에는 만료가 23시간이라 그날 회차가 통째로 빠졌다(2026-10-01 02:15 배포로 끊긴 회차의 락 때문에 08:43 따라잡기가
 * 건너뛰었다, #340). 이제 날짜 락은 실행 중 연장하고 짧은 만료로 잡으므로, 죽은 프로세스의 락은 곧 풀리고 다음 확인에서 이
 * 인스턴스가 잡아 돌린다. 오늘 성공 기록이 생기거나(다른 인스턴스가 끝냈다) · 락을 잡아 돌렸거나 · 날짜(서울)가 바뀌면 멈춘다 —
 * 다음 날 회차는 예약 실행의 몫이다. 이 인스턴스에서 실패(예외)로 끝난 배치는 다시 확인하지 않는다 — 재시도는 락 경합만이다. 다만
 * 다른 인스턴스가 실패로 끝낸 회차는 기록이 없으므로 다음 확인에서 이 인스턴스가 다시 돈다(실패한 날이 다음 기동에 다시 도는 것과
 * 같다). 기다리는 동안 앱이 내려가면 기다림을 끊고 멈춘다 — 도는 회차는 끊지 않는다.
 *
 * <p><b>네 슬롯</b> — 동시에 기동하면 모두 이 흐름을 탄다. 한 곳만 실행되는 것은 날짜 락이 보장하고, 못 잡은 슬롯은 스케줄러가
 * 한 줄 남기고 다음 배치로 넘어간다(그 배치는 뒤에 다시 확인한다). 그래서 슬롯마다 다른 배치를 동시에 돌 수 있다 — 날짜 락 키가 배치마다 달라 서로 막지 않고,
 * 같은 매물에서 겹치면 매물 분석 락이 한쪽을 건너뛰게 한다(예약 실행에서 두 배치가 겹칠 때와 같다).
 *
 * <p><b>실행 스레드</b> — 가상 스레드 하나. 준비 상태를 늦추지 않고, {@code @Scheduled} 스레드 풀을 쓰지 않으므로 예약 실행과
 * SSE 하트비트를 막지 않는다. 앞 배치가 실패해도 다음 배치는 돈다 — 예외는 로그로 남긴다. 경합 재확인도 같은 스레드에서
 * 기다린다 — 예약 스레드 풀을 붙잡지 않는다.
 *
 * <p><b>켜고 끄기</b> — {@code batch.startup-catchup.enabled}. 테스트 실행에서는 끈다.
 *
 * <p><b>패키지</b> — {@code scheduler/} 는 {@code @Scheduled} 반복 진입점의 자리라 두지 않았다. 이 클래스는 기동 때 한 번 도는
 * 진입점이고 스케줄러를 부르는 쪽이다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("batch.startup-catchup.enabled")
public class DailyBatchCatchUpRunner implements DisposableBean {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 없는 배치는 null — 스케줄러 빈이 뜨는 조건을 만족하지 않은 것이다. */
    private final PropertyRefreshScheduler propertyRefreshScheduler;
    private final RegistryRefreshScheduler registryRefreshScheduler;
    private final MockLedgerReplaceScheduler mockLedgerReplaceScheduler;
    private final BatchSuccessStore successStore;
    private final Clock clock;
    private final Executor startupExecutor;
    private final Duration retryInterval;
    /** 앱이 내려갈 때 열린다 — 경합 재확인의 기다림을 끊는다. */
    private final CountDownLatch shutdown = new CountDownLatch(1);

    @Autowired
    public DailyBatchCatchUpRunner(
            ObjectProvider<PropertyRefreshScheduler> propertyRefreshScheduler,
            ObjectProvider<RegistryRefreshScheduler> registryRefreshScheduler,
            ObjectProvider<MockLedgerReplaceScheduler> mockLedgerReplaceScheduler,
            BatchSuccessStore successStore,
            @Value("${batch.startup-catchup.retry-interval}") Duration retryInterval) {
        this(propertyRefreshScheduler.getIfAvailable(), registryRefreshScheduler.getIfAvailable(),
                mockLedgerReplaceScheduler.getIfAvailable(), successStore, Clock.system(SEOUL),
                task -> Thread.ofVirtual().name("daily-batch-catch-up").start(task), retryInterval);
    }

    DailyBatchCatchUpRunner(
            PropertyRefreshScheduler propertyRefreshScheduler,
            RegistryRefreshScheduler registryRefreshScheduler,
            MockLedgerReplaceScheduler mockLedgerReplaceScheduler,
            BatchSuccessStore successStore,
            Clock clock,
            Executor startupExecutor,
            Duration retryInterval) {
        this.propertyRefreshScheduler = propertyRefreshScheduler;
        this.registryRefreshScheduler = registryRefreshScheduler;
        this.mockLedgerReplaceScheduler = mockLedgerReplaceScheduler;
        this.successStore = successStore;
        this.clock = clock;
        this.startupExecutor = startupExecutor;
        this.retryInterval = retryInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        startupExecutor.execute(this::catchUp);
    }

    @Override
    public void destroy() {
        shutdown.countDown();
    }

    /** 기동 경로. 별도 스레드라 예외를 올릴 곳이 없다 — 배치마다 로그로 남기고 다음 배치로 넘어간다. */
    void catchUp() {
        LocalDate today = today();
        List<DailyBatch> contended = new ArrayList<>();
        for (DailyBatch batch : DailyBatch.values()) {
            BooleanSupplier entry = entryOf(batch);
            if (entry == null) {
                log.info("[기동 뒤 따라잡기] {} — 꺼져 있다 · 건너뜀", batch.getLabel());
                continue;
            }
            if (runIfMissing(batch, entry, today) == Outcome.CONTENDED) {
                contended.add(batch);
            }
        }
        retryContended(contended, today);
    }

    /** 락 경합으로 건너뛴 배치를 간격마다 다시 확인한다. 멈추는 조건은 클래스 주석 「락 경합은 다시 확인한다」. */
    private void retryContended(List<DailyBatch> contended, LocalDate today) {
        while (!contended.isEmpty()) {
            log.info("[기동 뒤 따라잡기] {} — 다른 인스턴스가 날짜 락을 잡고 있다 · {} 뒤 다시 확인",
                    contended.stream().map(DailyBatch::getLabel).toList(), retryInterval);
            if (awaitShutdown()) {
                log.info("[기동 뒤 따라잡기] 앱 종료 — 다시 확인을 멈춘다");
                return;
            }
            LocalDate now = today();
            if (!now.equals(today)) {
                log.info("[기동 뒤 따라잡기] 날짜가 바뀌었다({} → {}) — 다시 확인을 멈춘다 · 새 날 회차는 예약 실행이 돈다", today, now);
                return;
            }
            contended.removeIf(batch -> runIfMissing(batch, entryOf(batch), today) != Outcome.CONTENDED);
        }
    }

    /** 오늘 성공 기록이 없으면 진입점을 부른다. 예외는 로그로 남기고 실패로 돌려준다 — 다시 확인하지 않는다. */
    private Outcome runIfMissing(DailyBatch batch, BooleanSupplier entry, LocalDate today) {
        try {
            if (successStore.hasSucceeded(batch, today)) {
                log.info("[기동 뒤 따라잡기] {} — {} 회차는 이미 성공했다 · 건너뜀", batch.getLabel(), today);
                return Outcome.ALREADY_SUCCEEDED;
            }
            log.info("[기동 뒤 따라잡기] {} — {} 회차 실행", batch.getLabel(), today);
            return entry.getAsBoolean() ? Outcome.RAN : Outcome.CONTENDED;
        } catch (RuntimeException e) {
            log.error("[기동 뒤 따라잡기] {} — {} 회차 실패 · 다음 배치로 넘어간다", batch.getLabel(), today, e);
            return Outcome.FAILED;
        }
    }

    /** 재확인 간격만큼 기다린다. 그 사이 앱이 내려가면 true. */
    private boolean awaitShutdown() {
        try {
            return shutdown.await(retryInterval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private BooleanSupplier entryOf(DailyBatch batch) {
        return switch (batch) {
            case PROPERTY_REFRESH -> propertyRefreshScheduler == null
                    ? null : propertyRefreshScheduler::refreshProperties;
            case REGISTRY_REFRESH -> registryRefreshScheduler == null
                    ? null : registryRefreshScheduler::refreshWishlistedRegistries;
            case MOCK_LEDGER_REPLACE -> mockLedgerReplaceScheduler == null
                    ? null : mockLedgerReplaceScheduler::replaceMockLedgers;
        };
    }

    /** 한 배치를 한 번 확인한 결과. {@link #CONTENDED} 만 다시 확인한다. */
    private enum Outcome {
        ALREADY_SUCCEEDED, RAN, CONTENDED, FAILED
    }
}
