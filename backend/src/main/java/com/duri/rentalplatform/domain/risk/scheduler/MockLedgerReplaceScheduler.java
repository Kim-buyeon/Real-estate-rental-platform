package com.duri.rentalplatform.domain.risk.scheduler;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.MockLedgerReplaceJobLauncher;
import com.duri.rentalplatform.domain.risk.enums.DailyBatchRunOutcome;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mock 대장 교체 배치의 반복 실행 진입점. 시각은 설정 {@code risk.batch.mock-ledger-replace.cron}(서울). 기동 뒤에는
 * {@link com.duri.rentalplatform.domain.risk.startup.DailyBatchCatchUpRunner} 가 오늘 성공 기록이 없을 때만 이 메서드를
 * 한 번 부른다 — 하루 상한을 나눠 쓰는 배치라 기동마다 돌면 사용자 조회 몫까지 당겨 쓰므로, 이미 성공한 날에는 다시 돌지 않는다.
 *
 * <p><b>켜고 끄기</b> — {@code risk.batch.mock-ledger-replace.enabled} 가 참이고 <b>대장 연동이 real 일 때만</b> 뜬다. 테스트
 * 실행에서는 enabled 를 끈다. Mock · Fault 모드에서는 Mock 대장이 곧 그 모드의 대장이라 바꿀 것이 없다.
 *
 * <p><b>두 인스턴스</b> — 한쪽만 실행되는 것은 배치 시작기의 날짜 락이 보장하고, 여기서는 락을 못 잡은 쪽이 한 줄 남기고 끝낸다.
 * {@link RegistryRefreshScheduler} 와 같은 방식이다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("risk.batch.mock-ledger-replace.enabled")
@ConditionalOnProperty(prefix = "external.building-ledger", name = "mode", havingValue = "real")
public class MockLedgerReplaceScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final MockLedgerReplaceJobLauncher jobLauncher;
    private final Clock clock;

    @Autowired
    public MockLedgerReplaceScheduler(MockLedgerReplaceJobLauncher jobLauncher) {
        this(jobLauncher, Clock.system(SEOUL));
    }

    MockLedgerReplaceScheduler(MockLedgerReplaceJobLauncher jobLauncher, Clock clock) {
        this.jobLauncher = jobLauncher;
        this.clock = clock;
    }

    /**
     * 오늘(서울) 회차를 돌린다. 실패는 예외로 올린다.
     *
     * @return 날짜 락을 잡아 회차를 돌렸으면 {@link DailyBatchRunOutcome#RAN}, 다른 인스턴스가 잡고 있어 건너뛰었으면
     *         {@link DailyBatchRunOutcome#CONTENDED}. 예약 실행에서는 스케줄러가 반환값을 버리고(spring-context 7.0.9
     *         {@code @Scheduled} 주석), 기동 뒤 따라잡기가 건너뛴 배치를 다시 확인할지 가르는 데 쓴다
     */
    @Scheduled(cron = "${risk.batch.mock-ledger-replace.cron}", zone = "Asia/Seoul")
    public DailyBatchRunOutcome replaceMockLedgers() {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        try {
            jobLauncher.run(today);
            return DailyBatchRunOutcome.RAN;
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                throw e;
            }
            log.info("[Mock 대장 교체 배치] {} 회차는 다른 인스턴스가 실행 중이다 — 건너뜀", today);
            return DailyBatchRunOutcome.CONTENDED;
        }
    }
}
