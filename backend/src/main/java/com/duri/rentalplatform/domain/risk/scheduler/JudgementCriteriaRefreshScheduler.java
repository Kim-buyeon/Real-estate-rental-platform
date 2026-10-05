package com.duri.rentalplatform.domain.risk.scheduler;

import com.duri.rentalplatform.domain.risk.cache.JudgementCriteriaCache;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판정 기준표 슬롯 캐시를 다른 슬롯의 기준 변경에 맞춘다. 동작은 {@link JudgementCriteriaCache} 가 갖는다.
 *
 * <p><b>분산 락을 걸지 않는다</b> — 한 번만 실행하는 일이 아니다. 캐시가 슬롯마다 있으므로 모든 슬롯이 각자 돈다.
 *
 * <p>둘 다 고정 지연이다 — 앞 실행이 끝난 뒤 간격을 센다. Redis 가 느려 한 번이 타임아웃(2초)까지 걸려도 실행이 겹쳐 쌓이지 않는다.
 * 간격은 설정 {@code risk.criteria-cache.*}.
 */
@Component
public class JudgementCriteriaRefreshScheduler {

    private final JudgementCriteriaCache judgementCriteriaCache;

    public JudgementCriteriaRefreshScheduler(JudgementCriteriaCache judgementCriteriaCache) {
        this.judgementCriteriaCache = judgementCriteriaCache;
    }

    /** 버전 키가 바뀌었으면 다시 읽는다. */
    @Scheduled(fixedDelayString = "${risk.criteria-cache.version-check-interval}")
    public void reloadIfVersionChanged() {
        judgementCriteriaCache.reloadIfVersionChanged();
    }

    /**
     * 버전 키와 무관하게 다시 읽는다 — 버전 키를 못 올린 변경의 안전망. 기동 직후 첫 실행이 캐시를 채워 첫 요청이 기준표를 읽지 않게
     * 한다.
     */
    @Scheduled(fixedDelayString = "${risk.criteria-cache.full-reload-interval}")
    public void reloadAll() {
        judgementCriteriaCache.reload();
    }
}
