package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.property.vo.PropertyLoadReport;
import com.duri.rentalplatform.domain.property.vo.PropertyRefreshResult;
import lombok.Getter;

/**
 * 매물 갱신 배치(RISK-08) 한 회차의 집계. 적재 단계의 집계({@link PropertyLoadReport})와 판정 단계의 건수를 함께 든다.
 *
 * <p><b>단계 사이의 전달</b> — 없다. 적재 단계는 건수만 남기고, 판정 단계의 읽기는 대상(시세가 바뀐 매물 · 최신 판정이 없는
 * 매물)을 DB 에서 식별자 커서로 읽는다. 식별자 목록을 여기에 담으면 회차 동안 신규 매물 수만큼 쌓인다 — 2026-09-30 운영 회차가
 * 적재 직후 컨테이너 메모리 상한을 넘었다.
 *
 * <p>record 가 아니라 클래스인 이유는 배치가 도는 동안 누적되기 때문이다. 회차마다 새로 만들고 스텝은 한 스레드에서 차례로
 * 돌므로 동기화는 두지 않는다.
 */
@Getter
public class PropertyRefreshReport {

    /** 적재 단계의 집계 — 받은 건수 · 저장 · 시세 갱신 · 실패 사유. */
    private final PropertyLoadReport loadReport = new PropertyLoadReport();

    /** 적재 단계가 새로 저장한 매물 수. 이 매물들은 판정 단계의 「최신 판정 없음」 조회로 판정된다. */
    private int newProperties;

    /**
     * 적재 단계에서 시세 금액이 바뀐 매물 수. 판정 단계가 DB 의 재분석 대기 표시(V18)로 다시 찾는다. 판정 단계의
     * {@link #reanalyzed} 와 1:1 이 아니다 — 최신 판정이 없는 매물은 {@link #firstAnalyzed} 로 가고, 앞 회차에서 남은 표시도
     * 함께 읽힌다(#338).
     */
    private int priceChangedProperties;

    /** 판정을 시도한 매물 수. 아래 네 칸은 이 안에서 갈린다. */
    private int analysisTargets;

    /** 최신 판정이 없어 처음 판정한 매물 수(신규 매물 포함). */
    private int firstAnalyzed;

    /**
     * 재분석 대기 표시가 있고 최신 판정이 있어 재분석한 매물 수. 대기 표시가 있어도 최신 판정이 없으면 {@link #firstAnalyzed} 로
     * 센다 — 그래서 적재 쪽 {@link #priceChangedProperties} 와 1:1 이 아니다(#338). 앞 회차에서 재분석이 실패 · 경합으로 남은
     * 매물도 여기에 든다.
     */
    private int reanalyzed;

    /** 매물 락 경합으로 건너뛴 매물 수. 첫 판정이면 여전히 판정이 없어 다음 회차에 다시 대상이 된다. 재분석이면 사용자 재분석
     * 또는 등기 재조회 배치가 같은 매물을 판정하고 있었다 — 대기 표시는 그대로라 다음 회차가 다시 잡는다.
     */
    private int skipped;

    /** 판정이 실패한 매물 수. 다음 회차가 다시 잡는다 — 첫 판정은 여전히 판정이 없고, 재분석은 대기 표시가 그대로
     * 남는다. */
    private int failed;

    /** 적재 단계의 결과를 남긴다. */
    public void recordLoad(PropertyRefreshResult result) {
        newProperties = result.newProperties();
        priceChangedProperties = result.priceChangedProperties();
    }

    /** 판정 대상 한 건의 결과를 더한다. */
    public void addAttempt(PropertyRefreshAttempt attempt) {
        analysisTargets++;
        if (attempt.skipped()) {
            skipped++;
            return;
        }
        if (attempt.isFailed()) {
            failed++;
            return;
        }
        if (attempt.target().priceChanged()) {
            reanalyzed++;
        } else {
            firstAnalyzed++;
        }
    }

    public String summary() {
        return "적재[%s] · 신규 %d건 · 시세변경 %d건 · 판정 대상 %d건 · 첫 판정 %d건 · 재분석 %d건 · 건너뜀 %d건 · 실패 %d건"
                .formatted(loadReport.summary(), newProperties, priceChangedProperties, analysisTargets,
                        firstAnalyzed, reanalyzed, skipped, failed);
    }
}
