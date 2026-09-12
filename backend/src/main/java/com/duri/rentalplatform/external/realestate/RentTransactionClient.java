package com.duri.rentalplatform.external.realestate;

import java.util.List;

/**
 * 국토교통부 전월세 실거래가 연동.
 *
 * <p>구현은 셋이다 — Mock(고정 정상 응답) · Real(실제 호출) · Fault(지연 · 오류 주입).
 * 어느 구현이 뜨는지는 {@code external.rent-transaction.mode} 설정이 정한다.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로
 * 올라오며, 적재는 그 항목을 건너뛰고 기록한다 — 데이터 적재 설계서 1.4.
 */
public interface RentTransactionClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.*.instances} 키와
     * 같아야 한다 — 어긋나면 애노테이션이 조용히 기본 설정으로 돈다.
     *
     * <p>구현이 아니라 <b>인터페이스</b>가 갖는다. 격리 단위는 연동 대상이지 구현이 아니다. Real 과
     * Fault 가 같은 인스턴스를 써야, Fault 로 주입한 장애가 Real 이 쓸 서킷 설정 그대로 열리는지
     * 확인된다. 이름이 구현마다 따로 있으면 Fault 로 연 서킷이 Real 의 서킷과 다른 것이 된다.
     */
    String RESILIENCE_INSTANCE = "rentTransaction";

    /**
     * 한 시군구의 한 달치 전월세 실거래를 가져온다.
     *
     * <p>제공처가 페이지로 나누어 주더라도 구현이 전 페이지를 모아 한 번에 돌려준다. 호출자는 페이지를
     * 모른다.
     */
    List<RentTransaction> findRentTransactions(RentTransactionQuery query);
}
