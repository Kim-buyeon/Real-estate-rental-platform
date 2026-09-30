package com.duri.rentalplatform.domain.risk.startup;

import com.duri.rentalplatform.domain.risk.enums.DailyBatch;
import com.duri.rentalplatform.domain.risk.scheduler.MockLedgerReplaceScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.PropertyRefreshScheduler;
import com.duri.rentalplatform.domain.risk.scheduler.RegistryRefreshScheduler;
import com.duri.rentalplatform.domain.risk.store.BatchSuccessStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 실패한 날은 기록이 없으므로 다음 기동이나 다음 예약 시각에 다시 돈다. <b>회차 도중 노드를 멈춘 날은 조건부다</b> — 날짜 락이
 * 해제되지 않은 채 남고(만료 {@code *.lock-lease-time} 잠정 23시간), Redis 가 그 키를 들고 살아 있거나 재기동 뒤에도 키가 남아
 * 있으면 그날 회차는 락을 못 잡아 건너뛴다. 키가 남는지는 Redis 영속성 설정에 달렸고 이는 확인되지 않았다. 남으면 다음 날 회차부터
 * 다시 돈다.
 *
 * <p><b>네 슬롯</b> — 동시에 기동하면 모두 이 흐름을 탄다. 한 곳만 실행되는 것은 날짜 락이 보장하고, 못 잡은 슬롯은 스케줄러가
 * 한 줄 남기고 다음 배치로 넘어간다. 그래서 슬롯마다 다른 배치를 동시에 돌 수 있다 — 날짜 락 키가 배치마다 달라 서로 막지 않고,
 * 같은 매물에서 겹치면 매물 분석 락이 한쪽을 건너뛰게 한다(예약 실행에서 두 배치가 겹칠 때와 같다).
 *
 * <p><b>실행 스레드</b> — 가상 스레드 하나. 준비 상태를 늦추지 않고, {@code @Scheduled} 스레드 풀을 쓰지 않으므로 예약 실행과
 * SSE 하트비트를 막지 않는다. 앞 배치가 실패해도 다음 배치는 돈다 — 예외는 로그로 남긴다.
 *
 * <p><b>켜고 끄기</b> — {@code batch.startup-catchup.enabled}. 테스트 실행에서는 끈다.
 *
 * <p><b>패키지</b> — {@code scheduler/} 는 {@code @Scheduled} 반복 진입점의 자리라 두지 않았다. 이 클래스는 기동 때 한 번 도는
 * 진입점이고 스케줄러를 부르는 쪽이다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("batch.startup-catchup.enabled")
public class DailyBatchCatchUpRunner {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 없는 배치는 null — 스케줄러 빈이 뜨는 조건을 만족하지 않은 것이다. */
    private final PropertyRefreshScheduler propertyRefreshScheduler;
    private final RegistryRefreshScheduler registryRefreshScheduler;
    private final MockLedgerReplaceScheduler mockLedgerReplaceScheduler;
    private final BatchSuccessStore successStore;
    private final Clock clock;
    private final Executor startupExecutor;

    @Autowired
    public DailyBatchCatchUpRunner(
            ObjectProvider<PropertyRefreshScheduler> propertyRefreshScheduler,
            ObjectProvider<RegistryRefreshScheduler> registryRefreshScheduler,
            ObjectProvider<MockLedgerReplaceScheduler> mockLedgerReplaceScheduler,
            BatchSuccessStore successStore) {
        this(propertyRefreshScheduler.getIfAvailable(), registryRefreshScheduler.getIfAvailable(),
                mockLedgerReplaceScheduler.getIfAvailable(), successStore, Clock.system(SEOUL),
                task -> Thread.ofVirtual().name("daily-batch-catch-up").start(task));
    }

    DailyBatchCatchUpRunner(
            PropertyRefreshScheduler propertyRefreshScheduler,
            RegistryRefreshScheduler registryRefreshScheduler,
            MockLedgerReplaceScheduler mockLedgerReplaceScheduler,
            BatchSuccessStore successStore,
            Clock clock,
            Executor startupExecutor) {
        this.propertyRefreshScheduler = propertyRefreshScheduler;
        this.registryRefreshScheduler = registryRefreshScheduler;
        this.mockLedgerReplaceScheduler = mockLedgerReplaceScheduler;
        this.successStore = successStore;
        this.clock = clock;
        this.startupExecutor = startupExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        startupExecutor.execute(this::catchUp);
    }

    /** 기동 경로. 별도 스레드라 예외를 올릴 곳이 없다 — 배치마다 로그로 남기고 다음 배치로 넘어간다. */
    void catchUp() {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        for (DailyBatch batch : DailyBatch.values()) {
            Runnable entry = entryOf(batch);
            if (entry == null) {
                log.info("[기동 뒤 따라잡기] {} — 꺼져 있다 · 건너뜀", batch.getLabel());
                continue;
            }
            try {
                if (successStore.hasSucceeded(batch, today)) {
                    log.info("[기동 뒤 따라잡기] {} — {} 회차는 이미 성공했다 · 건너뜀", batch.getLabel(), today);
                    continue;
                }
                log.info("[기동 뒤 따라잡기] {} — {} 회차 실행", batch.getLabel(), today);
                entry.run();
            } catch (RuntimeException e) {
                log.error("[기동 뒤 따라잡기] {} — {} 회차 실패 · 다음 배치로 넘어간다", batch.getLabel(), today, e);
            }
        }
    }

    private Runnable entryOf(DailyBatch batch) {
        return switch (batch) {
            case PROPERTY_REFRESH -> propertyRefreshScheduler == null
                    ? null : propertyRefreshScheduler::refreshProperties;
            case REGISTRY_REFRESH -> registryRefreshScheduler == null
                    ? null : registryRefreshScheduler::refreshWishlistedRegistries;
            case MOCK_LEDGER_REPLACE -> mockLedgerReplaceScheduler == null
                    ? null : mockLedgerReplaceScheduler::replaceMockLedgers;
        };
    }
}
