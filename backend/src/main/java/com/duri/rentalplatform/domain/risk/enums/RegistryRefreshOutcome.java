package com.duri.rentalplatform.domain.risk.enums;

/**
 * 등기를 다시 뗀 결과. 재분석 여부와 변동 알림 여부가 갈리므로 참 · 거짓 하나로 줄이지 않는다 — 처음 수집은 분석이 필요하지만
 * 비교할 이전 내용이 없어 변동 알림 대상이 아니다.
 */
public enum RegistryRefreshOutcome {

    /** 저장된 등기가 없어 새로 저장했다. */
    COLLECTED,

    /** 갑구 · 을구가 달라 이력을 교체했다. */
    CHANGED,

    /** 저장된 갑구 · 을구와 같아 아무것도 쓰지 않았다. */
    UNCHANGED;

    /** 저장된 등기 내용이 이번 호출로 바뀌었는가 — 위험도를 다시 판정해야 하는가. */
    public boolean isModified() {
        return this != UNCHANGED;
    }
}
