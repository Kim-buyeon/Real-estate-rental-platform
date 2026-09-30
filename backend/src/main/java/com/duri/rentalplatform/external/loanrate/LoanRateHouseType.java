package com.duri.rentalplatform.external.loanrate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 금리 조회의 주택 유형. 적재 경로가 만드는 매물 유형(아파트 · 오피스텔)만 둔다 — 상수명은 매물 유형과 같다.
 *
 * <p>코드는 제공처 문서의 {@code houseTycd} 값이다 — https://www.data.go.kr/data/15082044/openapi.do 요청변수 표
 * 「01: 다세대 … 06: 아파트 … 10: 오피스텔 …」(확인일 2026-09-30).
 */
@Getter
@RequiredArgsConstructor
public enum LoanRateHouseType {
    APARTMENT("06"),
    OFFICETEL("10");

    private final String code;
}
