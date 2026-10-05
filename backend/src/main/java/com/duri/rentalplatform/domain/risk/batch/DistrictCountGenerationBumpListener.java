package com.duri.rentalplatform.domain.risk.batch;

import com.duri.rentalplatform.domain.property.cache.DistrictCountCache;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * 매물 수 · 등급 분포를 바꾸는 배치가 끝나면 자치구 집계 캐시의 세대를 올린다(#406). 매물 갱신 · 등기 재조회 · Mock 대장 교체 세
 * 배치의 Job 조립이 붙인다.
 *
 * <p><b>Job 이 끝난 뒤</b>(모든 청크 커밋 뒤) 한 번 — 도중에 올리면 판정이 끝나기 전의 분포가 새 세대로 담겨 다음 세대 변경까지(최대
 * Redis TTL) 남는다. <b>결과와 무관하게</b> 올린다 — 실패 · 중단으로 끝나도 그때까지 저장한 판정이 이미 반영돼 있다. Redis 실패는
 * 저장소가 경고만 남긴다.
 *
 * <p>사용자 재분석 요청(건당)은 세대를 올리지 않는다 — 한 건마다 모든 슬롯의 캐시를 비우면 캐시가 무의미하다. 그 변화는 Redis TTL
 * 안에 보인다 — 아키텍처 설계서(성능) 1.3 「낡음 상한」.
 */
public class DistrictCountGenerationBumpListener implements JobExecutionListener {

    private final DistrictCountCache districtCountCache;

    public DistrictCountGenerationBumpListener(DistrictCountCache districtCountCache) {
        this.districtCountCache = districtCountCache;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        districtCountCache.bumpGeneration();
    }
}
