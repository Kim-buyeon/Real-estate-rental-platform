package com.duri.rentalplatform.domain.risk.vo;

/**
 * 명의 · 문서 정합 확인(RISK-04) 결과. 필드 이름은 위험도 응답 {@code consistency} 와 같다.
 *
 * <p>대장에서 오는 세 항목(주소 · 위반건축물 · 면적)은 null 이 「확인하지 못함」이다 — 뗄 대장이 없거나 대장이 그 항목을 주지
 * 않을 때다. 보증 판정은 null 을 가입 불가 사유로 쓰지 않는다.
 *
 * @param ownerNameMatched  등기상 현재 소유자 = 임대인
 * @param addressMatched    대장 주소 = 등기 주소. 대장이 없으면 null
 * @param violationBuilding 위반건축물인가. 확인하지 못했으면 null — 계약 전 대장 열람 안내 대상이다
 * @param areaMatched       등기 표제부 전용면적 = 대장 전용면적. 대장이 없으면 null. 보증 조건이 아니라 안내 항목이다
 */
public record ConsistencyResult(
        boolean ownerNameMatched,
        Boolean addressMatched,
        Boolean violationBuilding,
        Boolean areaMatched
) {

    /** 위반건축물 여부를 대장에서 확인하지 못했는가 — 「위반건축물 확인 불가 — 계약 전 건축물대장 열람 필요」 안내 대상. */
    public boolean violationBuildingUnverified() {
        return violationBuilding == null;
    }
}
