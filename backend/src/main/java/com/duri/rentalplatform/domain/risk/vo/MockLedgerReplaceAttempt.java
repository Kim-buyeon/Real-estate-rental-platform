package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;

/**
 * Mock 대장 교체 배치가 매물 하나를 처리한 결과. 배치 스텝의 처리 단계가 만들고 쓰기 단계가 집계한다.
 *
 * <p>실패를 값으로 돌려주는 이유는 {@link RegistryRefreshAttempt} 와 같다 — 락 경합과 연동 실패가 같은 예외라 던지면 「건너뜀」과
 * 「실패」를 가를 수 없고, 스텝은 처리 단계의 예외로 회차를 멈춘다.
 *
 * @param outcome    대장을 다시 떼어 본 결과. 교체 · 삭제는 저장까지 마쳤을 때만 담는다 — 떼기 · 저장 중에 실패했거나
 *                   건너뛰었으면 null
 * @param reanalyzed 위험도 재분석까지 끝냈는가
 * @param skipped    매물 락 경합으로 이번 회차에 손대지 않았는가
 * @param failure    처리 중 난 예외. 성공 · 건너뜀이면 null
 */
public record MockLedgerReplaceAttempt(
        LedgerReplacementOutcome outcome,
        boolean reanalyzed,
        boolean skipped,
        RuntimeException failure
) {

    /** 락 안에서 끝까지 처리했다. */
    public static MockLedgerReplaceAttempt completed(LedgerReplacementOutcome outcome, boolean reanalyzed) {
        return new MockLedgerReplaceAttempt(outcome, reanalyzed, false, null);
    }

    /** 처리 중 실패했다. 교체 · 삭제가 끝난 뒤의 실패(재분석 실패)면 그 결과를 함께 담는다. */
    public static MockLedgerReplaceAttempt failed(LedgerReplacementOutcome outcome, RuntimeException failure) {
        return new MockLedgerReplaceAttempt(outcome, false, false, failure);
    }

    /** 같은 매물을 다른 요청이 처리 중이라 건너뛰었다. */
    public static MockLedgerReplaceAttempt lockContended() {
        return new MockLedgerReplaceAttempt(null, false, true, null);
    }

    public boolean isFailed() {
        return failure != null;
    }
}
