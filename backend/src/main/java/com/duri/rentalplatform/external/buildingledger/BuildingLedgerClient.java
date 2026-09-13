package com.duri.rentalplatform.external.buildingledger;

/**
 * 국토교통부 건축물대장 연동.
 *
 * <p><b>구현은 Mock · Fault 둘이다.</b> Real 은 활용 신청 승인과, 요청 파라미터인 시군구 · 법정동 코드와 번 · 지를
 * 매물이 저장하게 되는 변경에서 붙인다. 어느 구현이 뜨는지는 {@code external.building-ledger.mode} 가 정한다.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로 올라오며, 수집
 * 서비스는 아무것도 저장하지 않고 그대로 올린다 — 위반건축물 여부는 판정 입력이라 빈 값이나 기본값으로 채울 수 없다.
 */
public interface BuildingLedgerClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.*.instances} 키와 같아야 한다 —
     * 어긋나면 애노테이션이 조용히 기본 설정으로 돈다.
     *
     * <p>구현이 아니라 인터페이스가 갖는다. Real 과 Fault 가 같은 인스턴스를 써야 Fault 로 연 서킷이 Real 의 서킷과
     * 같은 것이 된다.
     */
    String RESILIENCE_INSTANCE = "buildingLedger";

    /** 매물의 건축물대장을 떼어 온다. */
    BuildingLedgerDocument fetch(BuildingLedgerLookup lookup);
}
