package com.duri.rentalplatform.external.loanrate;

import java.util.List;

/**
 * 한국주택금융공사(HF) 전세자금대출 금리 연동 — 은행별 한 달치 금리.
 *
 * <p>구현은 셋이다 — Mock(고정 정상 응답) · Real(실제 호출) · Fault(지연 · 오류 주입). 어느 구현이 뜨는지는
 * {@code external.jeonse-loan-rate.mode} 설정이 정한다.
 *
 * <p>호출은 트랜잭션 밖에서 한다. 실패는 {@code BusinessException(EXTERNAL_API_UNAVAILABLE)} 으로 올라오며, 대출 상품 갱신은
 * 그 주택 유형을 건너뛰고 기존 행을 그대로 둔다 — 데이터 적재 설계서 1.5 「실패해도 기존 데이터를 훼손하지 않는다」.
 */
public interface JeonseLoanRateClient {

    /**
     * Resilience4j 인스턴스 이름. {@code application.yml} 의 {@code resilience4j.*.instances} 키와 같아야 한다. Real 과 Fault
     * 가 같은 인스턴스를 쓴다 — 격리 단위는 연동 대상이지 구현이 아니다.
     */
    String RESILIENCE_INSTANCE = "jeonseLoanRate";

    /**
     * 한 달 · 한 주택 유형의 은행별 금리를 가져온다. 제공처가 페이지로 나누어 주더라도 구현이 모아 한 번에 돌려준다.
     *
     * @return 은행별 금리. 그 달 취급 실적이 없으면 빈 목록 — 실패는 빈 목록이 아니라 예외다
     */
    List<BankLoanRate> findBankLoanRates(LoanRateQuery query);
}
