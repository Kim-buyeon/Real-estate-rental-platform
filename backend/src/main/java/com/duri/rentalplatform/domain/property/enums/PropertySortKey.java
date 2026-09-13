package com.duri.rentalplatform.domain.property.enums;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 매물 목록 정렬 기준. API 명세서(매물) 1.3 「보증금 · 전세가율 · 등록일 기준」.
 *
 * <p>요청의 {@code sort} 값을 SQL 에 그대로 붙이지 않기 위한 허용 목록이다. 매퍼 XML 은 이 상수로만
 * 정렬 컬럼을 고른다.
 */
@Getter
@RequiredArgsConstructor
public enum PropertySortKey {
    DEPOSIT("deposit"),
    DEBT_RATIO("debtRatio"),
    REGISTERED_AT("registeredAt");

    /** 요청 파라미터에 쓰는 이름. 응답 필드명과 같다. */
    private final String paramName;

    public static Optional<PropertySortKey> fromParamName(String name) {
        return Arrays.stream(values()).filter(k -> k.paramName.equals(name)).findFirst();
    }
}
