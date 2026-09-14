package com.duri.rentalplatform.domain.risk.vo;

import lombok.Getter;

/**
 * 관심 매물 등기 재조회 배치(RISK-08) 한 회차의 집계.
 *
 * <p>건너뜀과 실패를 한 칸에 몰지 않는다. 건너뜀은 같은 매물을 사용자 재분석이 이미 처리 중이라 이번 회차에 손대지 않은 것이고,
 * 실패는 등기 · 대장 수집이나 분석이 끝나지 못한 것이다 — 앞은 대응이 필요 없고 뒤는 원인을 봐야 한다.
 *
 * <p>record 가 아니라 클래스인 이유는 배치가 도는 동안 누적되기 때문이다 — {@code PropertyLoadReport} 와 같다. 회차마다 새로
 * 만들어 그 회차의 쓰기 단계에만 넘기고, 스텝은 한 스레드에서 청크를 차례로 쓰므로 동기화는 두지 않는다.
 */
@Getter
public class RegistryRefreshReport {

    /** 처리를 시도한 매물 수. 나머지 네 칸은 이 안에서 갈린다(변동 뒤 재분석 실패는 변동과 실패에 함께 센다). */
    private int targets;

    /** 등기 내용이 바뀐 매물 수 — 이력 교체와 첫 수집을 모두 센다. 둘 다 위험도를 다시 판정해야 한다. */
    private int modified;

    /** 재분석까지 끝낸 매물 수 — 등기가 바뀌었거나 분석이 등기보다 뒤처져 있던 매물. */
    private int reanalyzed;

    /** 매물 락 경합으로 이번 회차에 건너뛴 매물 수. */
    private int skipped;

    /** 재조회 또는 재분석이 실패한 매물 수. */
    private int failed;

    public void addTarget() {
        targets++;
    }

    public void addModified() {
        modified++;
    }

    public void addReanalyzed() {
        reanalyzed++;
    }

    public void addSkipped() {
        skipped++;
    }

    public void addFailed() {
        failed++;
    }

    public String summary() {
        return "대상 %d건 · 변동 %d건 · 재분석 %d건 · 건너뜀 %d건 · 실패 %d건"
                .formatted(targets, modified, reanalyzed, skipped, failed);
    }
}
