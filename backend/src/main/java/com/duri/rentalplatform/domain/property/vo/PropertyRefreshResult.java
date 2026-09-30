package com.duri.rentalplatform.domain.property.vo;

/**
 * 갱신 적재(RISK-08) 한 번의 결과 건수. 실패 사유는 {@link PropertyLoadReport} 가 갖는다.
 *
 * <p><b>식별자를 담지 않는다</b> — 판정 단계는 대상을 DB 에서 식별자 커서로 읽는다. 신규 매물은 「최신 판정 없음」으로, 시세가 바뀐
 * 매물은 적재 쓰기 서비스가 세운 재분석 대기 표시(V18)로 잡힌다. 식별자 목록을 넘기면 회차 동안 신규 매물 수만큼(2026-09-30 운영
 * 23만여 건) 메모리에 쌓인다.
 *
 * @param newProperties          이번 적재가 새로 저장한 매물 수
 * @param priceChangedProperties 자연키가 같은 기존 매물 중 시세 금액이 바뀌어 갱신한 매물 수 — 데이터 적재 설계서 1.5 「시세가 실제로
 *                               변경된 매물만 재분석 대상으로 표시」. 기준일만 바뀐 매물은 들지 않는다 — 판정 입력이 그대로다
 */
public record PropertyRefreshResult(
        int newProperties,
        int priceChangedProperties
) {
}
