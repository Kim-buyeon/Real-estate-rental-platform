package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 등기 수집 출처. {@code building_registry.data_source} 에 저장하고 등기 이력 응답의 {@code dataSource} 로
 * 나간다 — API 명세서(위험도 분석) 1.3 예시 {@code "MOCK"}.
 *
 * <p>등기부등본은 개방 API 가 없어 Mock 어댑터만 있다(데이터 적재 설계서 1.3). 중계 서비스가 붙는
 * 변경에서 그 출처 값을 더한다.
 */
@Getter
@RequiredArgsConstructor
public enum RegistryDataSource {
    MOCK("Mock 어댑터");

    private final String label;
}
