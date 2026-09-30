package com.duri.rentalplatform.external.realestate;

import java.util.List;

/**
 * 국토교통부 매매 실거래가 연동. 매물 시세({@code market_price})를 만드는 표본이다 — 같은 유형 · 법정동 · 면적대 매매 실거래가의
 * 중앙값(비즈니스 로직 정의서 2장 · 4장).
 *
 * <p>구현은 셋이다 — Mock(고정 정상 응답) · Real(실제 호출) · Fault(지연 · 오류 주입).
 * 어느 구현이 뜨는지는 {@code external.sale-transaction.mode} 설정이 정한다.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로 올라오며, 적재는 그 달을
 * 건너뛰고 기록한다 — 데이터 적재 설계서 1.4.
 */
public interface SaleTransactionClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.*.instances} 키와 같아야 한다.
     * 구현이 아니라 인터페이스가 갖는 이유는 {@link RentTransactionClient#RESILIENCE_INSTANCE} 와 같다.
     */
    String RESILIENCE_INSTANCE = "saleTransaction";

    /**
     * 한 시군구의 한 달치 매매 실거래를 가져온다. 해제된 거래도 {@link SaleTransaction#cancelled()} 로 표시해 그대로
     * 돌려준다 — 표본에서 뺄지는 시세 산출이 정한다.
     *
     * <p>제공처가 페이지로 나누어 주더라도 구현이 전 페이지를 모아 한 번에 돌려준다. 호출자는 페이지를 모른다.
     */
    List<SaleTransaction> findSaleTransactions(SaleTransactionQuery query);
}
