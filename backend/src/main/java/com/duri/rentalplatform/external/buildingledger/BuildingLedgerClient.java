package com.duri.rentalplatform.external.buildingledger;

import java.util.Optional;

/**
 * 국토교통부 건축물대장 연동. 어느 구현이 뜨는지는 {@code external.building-ledger.mode} 가 정한다 — Mock · Real · Fault.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로 올라오며, 수집
 * 서비스는 아무것도 저장하지 않고 그대로 올린다 — 대장 항목은 판정 입력이라 빈 값이나 기본값으로 채울 수 없다.
 *
 * <p><b>빈 값은 「뗄 대장이 없다」이다.</b> 조회 키가 없는 매물이거나, 그 필지에 쓸 수 있는 표제부가 없을 때다. 장애가
 * 아니므로 예외로 올리지 않는다 — 장애와 같은 값이면 한 매물의 자료 문제로 서킷이 열린다. Real 은 일일 호출 상한에 닿았을
 * 때도 빈 값을 준다({@link BuildingLedgerDailyQuota}) — 둘을 가려야 하는 쪽은 상한의 남은 수를 함께 본다.
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

    /** 매물의 건축물대장을 떼어 온다. 뗄 대장이 없으면 빈 값. */
    Optional<BuildingLedgerDocument> fetch(BuildingLedgerLookup lookup);
}
