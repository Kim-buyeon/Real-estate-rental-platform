package com.duri.rentalplatform.external.registry;

/**
 * 등기부등본 연동. 개방 API 가 없고 유료 중계 서비스가 필요하다 — 데이터 적재 설계서 1.2 · 1.3.
 *
 * <p><b>구현은 Mock 하나다.</b> 실제 연동 대상이 정해지지 않아 Real 이 없고, Real 이 없으면 격리할 호출이
 * 없으므로 Fault · 재시도 · 서킷도 두지 않는다. 그래서 Resilience4j 인스턴스 이름 상수도 아직 없다 — 중계
 * 서비스가 붙는 변경에서 Real · Fault 와 함께 더한다. 어느 구현이 뜨는지는 {@code external.registry.mode}
 * 가 정한다.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 호출당 비용이 드는 연동이라 수집한 결과는 DB 에 보관하고 다시 부르지 않는다.
 */
public interface RegistryClient {

    /** 매물의 등기부등본을 떼어 온다. */
    RegistryDocument fetch(RegistryLookup lookup);
}
