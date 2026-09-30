package com.duri.rentalplatform.domain.risk.vo;

import lombok.Getter;

/**
 * Mock 대장 교체 배치 한 회차의 집계.
 *
 * <p>record 가 아니라 클래스인 이유는 {@link RegistryRefreshReport} 와 같다 — 배치가 도는 동안 누적된다. 회차마다 새로 만들고,
 * 스텝은 한 스레드에서 청크를 차례로 쓰므로 동기화는 두지 않는다.
 */
@Getter
public class MockLedgerReplaceReport {

    /** 처리를 시도한 매물 수. */
    private int targets;

    /** Mock 대장을 건축HUB 대장으로 바꾼 매물 수. */
    private int replaced;

    /** 뗄 대장이 없어 Mock 대장을 지운 매물 수 — 대장 항목은 확인 불가가 된다. */
    private int removed;

    /** 재분석까지 끝낸 매물 수. */
    private int reanalyzed;

    /** 일일 호출 상한에 닿아 떼어 보지 못하고 Mock 을 남긴 매물 수. 0 보다 크면 그 뒤로 회차를 멈췄다. */
    private int quotaExhausted;

    /** 매물 락 경합으로 건너뛴 매물 수. */
    private int skipped;

    /** 대장 떼기 · 교체 · 삭제 · 재분석이 실패한 매물 수(교체 · 삭제 뒤 재분석 실패는 교체 · 삭제와 실패에 함께 센다). */
    private int failed;

    public void addTarget() {
        targets++;
    }

    public void addReplaced() {
        replaced++;
    }

    public void addRemoved() {
        removed++;
    }

    public void addReanalyzed() {
        reanalyzed++;
    }

    public void addQuotaExhausted() {
        quotaExhausted++;
    }

    public void addSkipped() {
        skipped++;
    }

    public void addFailed() {
        failed++;
    }

    public String summary() {
        return "대상 %d건 · 교체 %d건 · 삭제 %d건 · 재분석 %d건 · 상한 %d건 · 건너뜀 %d건 · 실패 %d건"
                .formatted(targets, replaced, removed, reanalyzed, quotaExhausted, skipped, failed);
    }
}
