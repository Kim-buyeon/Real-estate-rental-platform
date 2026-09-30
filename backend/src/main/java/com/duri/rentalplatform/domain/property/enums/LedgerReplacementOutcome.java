package com.duri.rentalplatform.domain.property.enums;

/**
 * Mock 대장 교체에서 대장을 다시 떼어 본 결과. 교체 배치가 이 값으로 교체 · 삭제 · 중단을 가른다.
 */
public enum LedgerReplacementOutcome {

    /** 대장이 이미 Mock 이 아니다(다른 경로가 교체 · 삭제했다). 손대지 않는다. */
    NOT_MOCK,

    /** 건축HUB 대장을 뗐다. Mock 행을 이 대장으로 바꾼다. */
    FETCHED,

    /** 뗄 대장이 없다 — 조회 키가 없거나 필지에 맞는 표제부가 없다. Mock 행을 지운다(대장 항목은 확인 불가). */
    NOT_FOUND,

    /** 일일 호출 상한에 닿아 떼어 보지 못했다. Mock 행을 그대로 두고 그날의 교체를 멈춘다. */
    QUOTA_EXHAUSTED
}
