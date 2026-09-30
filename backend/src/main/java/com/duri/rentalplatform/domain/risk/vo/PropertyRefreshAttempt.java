package com.duri.rentalplatform.domain.risk.vo;

/**
 * 매물 갱신 배치(RISK-08)가 판정 대상 한 건을 처리한 결과. 배치 스텝의 처리 단계가 만들고 쓰기 단계가 집계한다.
 *
 * <p>실패를 예외가 아니라 값으로 돌려주는 이유는 {@link RegistryRefreshAttempt} 와 같다 — 락 경합과 판정 실패(등기 · 대장 수집)가
 * 같은 {@code EXTERNAL_API_UNAVAILABLE} 이라 던지면 「건너뜀」과 「실패」를 가를 수 없고, 스텝은 처리 단계의 예외로 회차를
 * 멈춘다.
 *
 * @param target   처리한 대상
 * @param analyzed 판정까지 끝냈는가
 * @param skipped  매물 락 경합으로 이번 회차에 손대지 않았는가
 * @param failure  판정 또는 락 획득에서 난 예외. 성공 · 건너뜀이면 null
 */
public record PropertyRefreshAttempt(
        PropertyRefreshTarget target,
        boolean analyzed,
        boolean skipped,
        RuntimeException failure
) {

    /** 락 안에서 판정까지 끝냈다. */
    public static PropertyRefreshAttempt analyzed(PropertyRefreshTarget target) {
        return new PropertyRefreshAttempt(target, true, false, null);
    }

    /** 판정 중 또는 락 획득 중 실패했다. */
    public static PropertyRefreshAttempt failed(PropertyRefreshTarget target, RuntimeException failure) {
        return new PropertyRefreshAttempt(target, false, false, failure);
    }

    /** 같은 매물을 다른 요청(사용자 재분석 · 등기 재조회 배치)이 처리 중이라 건너뛰었다. */
    public static PropertyRefreshAttempt lockContended(PropertyRefreshTarget target) {
        return new PropertyRefreshAttempt(target, false, true, null);
    }

    public boolean isFailed() {
        return failure != null;
    }
}
