package com.duri.rentalplatform.domain.risk.scheduler;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.risk.batch.PropertyRefreshJobLauncher;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매물 갱신 배치(RISK-08)의 반복 실행 진입점. 시각은 설정 {@code property.batch.refresh.cron}(서울).
 *
 * <p><b>켜고 끄기</b> — {@code property.batch.refresh.enabled} 가 참일 때만 뜬다. 테스트 실행에서는 끈다 — 테스트가 배치 시각에
 * 돌면 공유 컨테이너에 매물 적재와 판정이 끼어든다.
 *
 * <p><b>두 인스턴스</b> — 두 프로세스가 같은 시각에 이 메서드를 부른다. 한쪽만 실행되는 것은 배치 시작기의 날짜 락이 보장하고,
 * 여기서는 락을 못 잡은 쪽이 한 줄 남기고 끝낸다. 날짜는 서울 기준으로 여기서 정해 넘긴다 — 두 인스턴스가 같은 키를 봐야 한다.
 * {@link RegistryRefreshScheduler} 와 같은 방식이다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("property.batch.refresh.enabled")
public class PropertyRefreshScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PropertyRefreshJobLauncher jobLauncher;
    private final Clock clock;

    @Autowired
    public PropertyRefreshScheduler(PropertyRefreshJobLauncher jobLauncher) {
        this(jobLauncher, Clock.system(SEOUL));
    }

    PropertyRefreshScheduler(PropertyRefreshJobLauncher jobLauncher, Clock clock) {
        this.jobLauncher = jobLauncher;
        this.clock = clock;
    }

    @Scheduled(cron = "${property.batch.refresh.cron}", zone = "Asia/Seoul")
    public void refreshProperties() {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        try {
            jobLauncher.run(today);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EXTERNAL_API_UNAVAILABLE) {
                throw e;
            }
            log.info("[매물 갱신 배치] {} 회차는 다른 인스턴스가 실행 중이다 — 건너뜀", today);
        }
    }
}
