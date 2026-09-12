package com.duri.rentalplatform.external.address;

import java.util.Optional;

/**
 * 도로명주소 주소 정규화 연동. 환경 변수 {@code ADDRESS_API_KEY}.
 *
 * <p>구현은 셋이다 — Mock · Real · Fault. 어느 구현이 뜨는지는 {@code external.address-normalize.mode}
 * 가 정한다.
 *
 * <p>찾지 못한 주소는 빈 값으로 돌려준다. 예외가 아니다 — 「없는 주소」와 「조회 실패」는 다르고,
 * 적재는 앞은 건너뛰고 뒤는 실패로 기록해야 한다.
 */
public interface AddressNormalizeClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.*.instances} 키와
     * 같아야 한다 — 어긋나면 애노테이션이 조용히 기본 설정으로 돈다.
     *
     * <p>구현이 아니라 <b>인터페이스</b>가 갖는다. 격리 단위는 연동 대상이지 구현이 아니다. Real 과
     * Fault 가 같은 인스턴스를 써야, Fault 로 주입한 장애가 Real 이 쓸 서킷 설정 그대로 열리는지
     * 확인된다. 이름이 구현마다 따로 있으면 Fault 로 연 서킷이 Real 의 서킷과 다른 것이 된다.
     */
    String RESILIENCE_INSTANCE = "addressNormalize";

    /**
     * 원문 주소를 정규화한다. 일치하는 주소가 없으면 빈 값.
     *
     * @throws com.duri.rentalplatform.common.BusinessException 연동이 응답하지 못할 때
     */
    Optional<NormalizedAddress> normalize(String rawAddress);
}
