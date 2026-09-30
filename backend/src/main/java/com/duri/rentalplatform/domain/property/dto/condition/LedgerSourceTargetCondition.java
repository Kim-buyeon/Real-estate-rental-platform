package com.duri.rentalplatform.domain.property.dto.condition;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;

/**
 * 수집 출처가 같은 대장을 가진 매물 식별자의 한 페이지 — Mock 대장 교체 배치가 대상을 나눠 읽는다.
 *
 * @param dataSource     대장 수집 출처
 * @param wishlistedOnly 참이면 누군가 관심 매물로 등록한 매물만
 * @param lastPropertyId 이전 페이지 마지막 매물 식별자. 첫 페이지는 null
 * @param limit          페이지 크기
 */
public record LedgerSourceTargetCondition(
        LedgerDataSource dataSource,
        boolean wishlistedOnly,
        Long lastPropertyId,
        int limit
) {
}
