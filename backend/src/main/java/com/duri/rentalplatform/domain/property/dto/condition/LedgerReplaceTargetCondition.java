package com.duri.rentalplatform.domain.property.dto.condition;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;

/**
 * Mock 대장 교체 배치 대상 매물 식별자의 한 페이지. 배치가 단계마다 대상을 나눠 읽는다.
 *
 * <p>대상은 두 갈래다 — 대장의 수집 출처가 {@code replaceSource} 인 매물, 대장 행이 없고 조회 키가 있는 매물. 둘 중 적어도 하나를
 * 고른다.
 *
 * @param replaceSource  이 출처의 대장을 가진 매물을 넣는다(교체 대상 — MOCK). null 이면 넣지 않는다
 * @param missingLedger  참이면 대장 행이 없고 조회 키가 있는 매물을 넣는다
 * @param wishlistedOnly 참이면 누군가 관심 매물로 등록한 매물만
 * @param lastPropertyId 이전 페이지 마지막 매물 식별자. 첫 페이지는 null
 * @param limit          페이지 크기
 */
public record LedgerReplaceTargetCondition(
        LedgerDataSource replaceSource,
        boolean missingLedger,
        boolean wishlistedOnly,
        Long lastPropertyId,
        int limit
) {

    public LedgerReplaceTargetCondition {
        if (replaceSource == null && !missingLedger) {
            throw new IllegalArgumentException("교체 대상 출처와 대장 없음 중 하나는 골라야 한다");
        }
    }
}
