package com.duri.rentalplatform.domain.loan.scheduler;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.loan.service.LoanProductRefreshService;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대출 상품 금리 갱신(LOAN-01)의 실행 진입점. 기준월은 실행일(서울)의 전달이다 — 제공처 금리는 대출 실행 월 단위로 집계된다.
 *
 * <p><b>두 경로</b>
 * <ul>
 *   <li>월 1회 — 설정 {@code loan.batch.rate-refresh.cron}(서울)
 *   <li>기동 뒤 1회 — 매물 유형 중 하나라도 기준월 행이 없으면(테이블이 비었거나 월 갱신을 놓쳤으면) 돌린다. 노드를 작업할 때만
 *       켜는 운용이라 월 1회 시각에 꺼져 있을 수 있다. 준비 상태를 늦추지 않도록 가상 스레드에서 돌린다
 * </ul>
 *
 * <p><b>켜고 끄기</b> — {@code loan.batch.rate-refresh.enabled} 가 참이고 <b>연동이 real 일 때만</b> 뜬다. 테스트 실행에서는
 * enabled 를 끈다 — 공유 컨테이너의 대출 상품 행에 갱신이 끼어든다. Mock · Fault 에서 띄우지 않는 이유는 가상 은행 행이 DB 에 들어가
 * 시드 대신 대표 상품이 되고, 한도가 지어낸 금리로 계산되기 때문이다. 연동을 real 로 켜지 않은 환경은 시드 예시 행으로 계산한다.
 *
 * <p><b>두 인스턴스</b> — 두 프로세스가 같은 시각에 부른다. 한쪽만 실행되는 것은 갱신 서비스의 기준월 락이 보장하고, 여기서는 락을
 * 못 잡은 쪽이 한 줄 남기고 끝낸다. {@code PropertyRefreshScheduler} 와 같은 방식이다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("loan.batch.rate-refresh.enabled")
@ConditionalOnProperty(prefix = "external.jeonse-loan-rate", name = "mode", havingValue = "real")
public class LoanRateRefreshScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final LoanProductRefreshService refreshService;
    private final Clock clock;
    private final Executor startupExecutor;

    @Autowired
    public LoanRateRefreshScheduler(LoanProductRefreshService refreshService) {
        this(refreshService, Clock.system(SEOUL),
                task -> Thread.ofVirtual().name("loan-rate-refresh-startup").start(task));
    }

    LoanRateRefreshScheduler(LoanProductRefreshService refreshService, Clock clock, Executor startupExecutor) {
        this.refreshService = refreshService;
        this.clock = clock;
        this.startupExecutor = startupExecutor;
    }

    @Scheduled(cron = "${loan.batch.rate-refresh.cron}", zone = "Asia/Seoul")
    public void refreshMonthly() {
        YearMonth baseMonth = baseMonth();
        try {
            refreshService.refresh(baseMonth);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                throw e;
            }
            log.info("[대출 금리 갱신] {} 회차는 다른 인스턴스가 실행 중이다 — 건너뜀", baseMonth);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartupIfStale() {
        startupExecutor.execute(this::refreshIfStale);
    }

    /** 기동 경로. 별도 스레드라 예외를 올릴 곳이 없다 — 로그로 남기고 끝낸다. 다음 기회는 월 1회 또는 다음 기동이다. */
    void refreshIfStale() {
        YearMonth baseMonth = baseMonth();
        try {
            if (refreshService.isStale(baseMonth)) {
                refreshMonthly();
            }
        } catch (RuntimeException e) {
            log.error("[대출 금리 갱신] 기동 뒤 갱신 실패 — {}", baseMonth, e);
        }
    }

    private YearMonth baseMonth() {
        return YearMonth.now(clock.withZone(SEOUL)).minusMonths(1);
    }
}
