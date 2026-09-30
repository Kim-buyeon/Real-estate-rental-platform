package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 건축물대장 수집 출처. {@code building_ledger.data_source} 에 저장한다.
 *
 * <p>{@code addressNormalizationRequired} — 대장이 준 주소를 적재와 같은 주소 정규화에 한 번 더 태워야 하는가. 명의 ·
 * 문서 정합(RISK-04)의 주소 대조는 정규화된 주소끼리의 완전 일치라, 대장 원문 주소(지번)를 그대로 두면 같은 필지도 표기
 * 차이로 불일치가 된다. Mock 은 매물 주소(이미 정규화된 값)를 그대로 쓰므로 다시 태우지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum LedgerDataSource {
    MOCK("Mock 어댑터", false),
    BUILDING_HUB("건축HUB 건축물대장정보", true);

    private final String label;
    private final boolean addressNormalizationRequired;
}
