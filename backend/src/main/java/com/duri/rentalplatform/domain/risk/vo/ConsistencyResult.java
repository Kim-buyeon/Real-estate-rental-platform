package com.duri.rentalplatform.domain.risk.vo;

/**
 * 명의 · 문서 정합 확인(RISK-04) 결과. 필드 이름은 위험도 응답 {@code consistency} 와 같다.
 *
 * @param ownerNameMatched  등기상 현재 소유자 = 임대인
 * @param addressMatched    대장 주소 = 등기 주소
 * @param violationBuilding 위반건축물인가
 * @param areaMatched       등기 표제부 전용면적 = 대장 전용면적. 보증 조건이 아니라 안내 항목이다
 */
public record ConsistencyResult(
        boolean ownerNameMatched,
        boolean addressMatched,
        boolean violationBuilding,
        boolean areaMatched
) {
}
