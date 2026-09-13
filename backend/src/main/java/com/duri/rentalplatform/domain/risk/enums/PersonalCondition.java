package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 시스템이 판정하지 않는 개인 자격 확인 사항. 위험도 응답 {@code personalConditions} 로 나간다 — API 명세서(위험도 분석)
 * 1.1, 비즈니스 로직 정의서 2장 「가입 시 추가 확인 사항」.
 */
@Getter
@RequiredArgsConstructor
public enum PersonalCondition {
    ANNUAL_INCOME("연소득 기준"),
    APPLICATION_DEADLINE("신청기한"),
    NEW_OR_RENEWAL("신규 · 갱신 계약 구분"),
    /** 주거용 오피스텔은 계약서 · 중개대상물 확인서에 「주거용」 표기가 필요하다. 시스템이 계약서를 보유하지 않는다. */
    RESIDENTIAL_USE_NOTATION("주거용 표기"),
    /** SGI 는 공인중개사를 통한 계약만 받는다. 계약 방식은 매물 데이터에 없다. */
    BROKER_CONTRACT("공인중개사 계약"),
    /** 대항력 요건. 계약 후 임차인이 하는 행위라 매물 데이터로 판정할 수 없다. */
    MOVE_IN_AND_FIXED_DATE("전입신고 · 확정일자");

    private final String label;
}
