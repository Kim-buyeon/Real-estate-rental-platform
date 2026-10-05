package com.duri.rentalplatform.domain.property.scheduler;

import com.duri.rentalplatform.domain.property.cache.DistrictCountCache;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자치구 집계 슬롯 로컬 캐시를 다른 슬롯의 세대 변경에 맞춘다. 동작은 {@link DistrictCountCache#refreshGeneration()} 이
 * 갖는다.
 *
 * <p><b>분산 락을 걸지 않는다</b> — 한 번만 실행하는 일이 아니다. 로컬 캐시가 슬롯마다 있으므로 모든 슬롯이 각자 돈다.
 *
 * <p>고정 지연이다 — 앞 실행이 끝난 뒤 간격을 센다. Redis 가 느려 한 번이 타임아웃까지 걸려도 실행이 겹쳐 쌓이지 않는다. 간격은
 * 설정 {@code property.district-counts.generation-check-interval}. 기동 직후 첫 실행이 세대를 읽어, 요청 경로가 세대를 읽는 일은
 * 그 전에 온 요청뿐이다.
 */
@Component
public class DistrictCountGenerationScheduler {

    private final DistrictCountCache districtCountCache;

    public DistrictCountGenerationScheduler(DistrictCountCache districtCountCache) {
        this.districtCountCache = districtCountCache;
    }

    /** 세대 키가 바뀌었으면 이 슬롯의 로컬 캐시를 비운다. */
    @Scheduled(fixedDelayString = "${property.district-counts.generation-check-interval}")
    public void refreshGeneration() {
        districtCountCache.refreshGeneration();
    }
}
