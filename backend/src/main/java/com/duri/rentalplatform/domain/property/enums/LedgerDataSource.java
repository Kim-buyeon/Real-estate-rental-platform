package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 건축물대장 수집 출처. {@code building_ledger.data_source} 에 저장한다.
 *
 * <p>건축물대장 연동은 아직 Mock 만 있다. Real 이 붙는 변경에서 그 출처 값을 더한다.
 */
@Getter
@RequiredArgsConstructor
public enum LedgerDataSource {
    MOCK("Mock 어댑터");

    private final String label;
}
