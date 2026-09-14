package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.RegistryRefreshOutcome;

/**
 * 배치가 매물 하나를 처리한 결과. 배치 스텝의 처리 단계가 만들고 쓰기 단계가 집계한다.
 *
 * <p>실패를 예외가 아니라 값으로 돌려주는 이유 — 락 경합도 등기 수집 실패도 같은 {@code EXTERNAL_API_UNAVAILABLE} 예외라, 둘 다
 * 던지면 부르는 쪽이 「건너뜀」과 「실패」를 가를 수 없다. 락 안의 실패를 여기에 담으면 락 밖으로 나오는 예외는 락을 못 잡은
 * 경우뿐이다. 또 스텝은 처리 단계에서 예외가 나면 청크를 되돌리고 멈추므로, 매물 하나의 실패로 회차 전체가 멈추지 않게 한다.
 *
 * @param outcome    등기 재조회 결과. 재조회 전에 실패했거나 건너뛰었으면 null
 * @param reanalyzed 위험도 재분석까지 끝냈는가
 * @param skipped    매물 락 경합으로 이번 회차에 손대지 않았는가
 * @param failure    재조회 · 재분석 또는 락 획득에서 난 예외. 성공 · 건너뜀이면 null
 */
public record RegistryRefreshAttempt(
        RegistryRefreshOutcome outcome,
        boolean reanalyzed,
        boolean skipped,
        RuntimeException failure
) {

    /** 락 안에서 끝까지 처리했다. */
    public static RegistryRefreshAttempt completed(RegistryRefreshOutcome outcome, boolean reanalyzed) {
        return new RegistryRefreshAttempt(outcome, reanalyzed, false, null);
    }

    /** 처리 중 실패했다. 재조회가 끝난 뒤의 실패면 그 결과를 함께 담는다. */
    public static RegistryRefreshAttempt failed(RegistryRefreshOutcome outcome, RuntimeException failure) {
        return new RegistryRefreshAttempt(outcome, false, false, failure);
    }

    /** 같은 매물을 다른 요청이 처리 중이라 건너뛰었다. */
    public static RegistryRefreshAttempt lockContended() {
        return new RegistryRefreshAttempt(null, false, true, null);
    }

    /** 등기 내용이 이번 재조회로 바뀌었는가. */
    public boolean modified() {
        return outcome != null && outcome.isModified();
    }

    public boolean isFailed() {
        return failure != null;
    }
}
