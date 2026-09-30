package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.LedgerReplacementOutcome;
import com.duri.rentalplatform.external.buildingledger.BuildingLedgerDocument;

/**
 * Mock 대장을 교체하려고 대장을 다시 떼어 본 결과.
 *
 * @param outcome  결과
 * @param document 뗀 대장. 주소 정규화까지 끝난 값이다. {@link LedgerReplacementOutcome#FETCHED} 일 때만 있다
 */
public record LedgerReplacement(
        LedgerReplacementOutcome outcome,
        BuildingLedgerDocument document
) {

    public static LedgerReplacement fetched(BuildingLedgerDocument document) {
        return new LedgerReplacement(LedgerReplacementOutcome.FETCHED, document);
    }

    public static LedgerReplacement of(LedgerReplacementOutcome outcome) {
        if (outcome == LedgerReplacementOutcome.FETCHED) {
            throw new IllegalArgumentException("뗀 대장은 fetched 로 만든다");
        }
        return new LedgerReplacement(outcome, null);
    }
}
