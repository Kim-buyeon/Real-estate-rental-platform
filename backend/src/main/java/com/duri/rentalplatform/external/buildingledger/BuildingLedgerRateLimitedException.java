package com.duri.rentalplatform.external.buildingledger;

/**
 * 건축HUB 초당 요청 한도에 걸려 이번 조회를 하지 못했다. 장애가 아니다 — 제공처는 멀쩡하고 잠시 뒤에는 받는다.
 *
 * <p>두 경우에 난다 — {@link BuildingLedgerRateLimiter} 가 기다림 상한 안에 몫을 받지 못했을 때, 제공처가
 * {@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR} 로 거절했을 때.
 *
 * <p><b>빈 값이 아니라 예외인 이유</b> — 빈 값은 「뗄 대장이 없다」라 Mock 대장 교체 배치가 Mock 행을 지운다. 일일 상한은 남은
 * 수({@link BuildingLedgerDailyQuota#remaining()})로 가를 수 있지만 초당 한도는 가를 단서가 없다.
 *
 * <p><b>서킷 · 재시도</b> — {@code application.yml} 의 {@code resilience4j.*.instances.buildingLedger.ignore-exceptions} 에
 * 넣어 서킷이 실패로 세지 않고 재시도하지 않는다. 폴백도 이 예외는 503 으로 바꾸지 않고 그대로 올린다
 * ({@link RealBuildingLedgerClient}). 받는 쪽(대장 수집 서비스)은 대장 없이 진행한다 — 일일 상한과 같은 처리다.
 *
 * <p><b>{@code BusinessException} 이 아닌 이유</b> — API 오류가 아니라 연동 계층 안의 신호다. 대장 수집 서비스가 잡아 응답까지
 * 가지 않는다. 서킷 · 재시도 설정이 예외 <b>타입</b>으로만 무시할 수 있어 따로 둔다 — {@code BusinessException} 으로 두면
 * {@code EXTERNAL_API_UNAVAILABLE} 장애까지 함께 무시된다. 대응하는 오류 코드도 API 명세에 없다.
 */
public class BuildingLedgerRateLimitedException extends RuntimeException {

    public BuildingLedgerRateLimitedException(String message) {
        super(message);
    }
}
