package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 하루 한 번 도는 위험도 배치. 성공 기록 키({@code batch:last-success:{key}})의 이름을 갖는다. 선언 순서가 기동 뒤 따라잡기의
 * 실행 순서다 — 매물 · 시세를 먼저 갱신해야 등기 재조회 · 대장 교체의 재분석이 새 시세로 판정한다.
 */
@Getter
@RequiredArgsConstructor
public enum DailyBatch {
    PROPERTY_REFRESH("property-refresh", "매물 갱신 배치"),
    REGISTRY_REFRESH("registry-refresh", "등기 재조회 배치"),
    MOCK_LEDGER_REPLACE("mock-ledger-replace", "Mock 대장 교체 배치");

    private final String key;
    private final String label;
}
